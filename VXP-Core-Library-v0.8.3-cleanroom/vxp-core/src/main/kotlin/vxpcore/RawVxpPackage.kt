package vxpcore

import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream
import java.util.zip.Inflater

/**
 * Parser for the raw Gameloft/ARMCC MRE VXP layout seen in late Nokia/S30+ titles.
 *
 * Layout used by these packages:
 *   zlib stream #0 -> position independent ARM/Thumb image
 *   zlib stream #1 -> initial RW data image
 *   remaining bytes -> application resource payload / tagged resource archive
 *
 * The ARM image contains its MRE import names as zero terminated ASCII strings and
 * small lazy-binding veneers that resolve a symbol through a host resolver pointer.
 */
object RawVxpPackage {
    data class ZStream(val compressedOffset: Int, val compressedSize: Int, val bytes: ByteArray)

    data class ImportStub(val name: String, val stubOffset: Int, val nameOffset: Int)

    data class Image(
        val code: ByteArray,
        val initialData: ByteArray,
        val resources: ByteArray,
        val streams: List<ZStream>,
        val imports: List<ImportStub>,
        val vmMainOffset: Int?,
        val dataSizeHint: Int,
        val bssSizeHint: Int,
        val resolverSlotOffset: Int = 0x80c
    )

    fun looksLikeRawArmZlib(input: ByteArray): Boolean {
        if (!looksLikeZlib(input, 0)) return false
        val first = inflateOne(input, 0) ?: return false
        val code = first.bytes
        if (code.size < 0x100) return false
        // Typical ARMCC scatter-loader prologue used by these VXP binaries:
        // ADD r8, pc, #imm ; LDM r8,{r0-r3} ; ADD r0,r0,r8 ...
        val w0 = u32(code, 0)
        val w1 = u32(code, 4)
        val armScatter = (w0 and 0x0ffff000) == 0x028f8000 && w1 == 0xe898000f.toInt()
        val hasMreMarker = indexOf(code, "mre-".toByteArray()) >= 0 || indexOf(code, "vm_reg_sysevt_callback".toByteArray()) >= 0
        return armScatter || hasMreMarker
    }

    fun parse(input: ByteArray): Image {
        require(looksLikeZlib(input, 0)) { "Raw ARM VXP does not start with zlib" }
        val streams = mutableListOf<ZStream>()
        var off = 0
        repeat(8) {
            if (!looksLikeZlib(input, off)) return@repeat
            val s = inflateOne(input, off) ?: return@repeat
            streams += s
            off += s.compressedSize
        }
        require(streams.isNotEmpty()) { "No zlib stream decoded" }
        val code = streams[0].bytes
        require(code.size >= 0x100) { "Raw ARM code stream too small" }
        val initialData = streams.getOrNull(1)?.bytes ?: ByteArray(0)
        val resources = if (off < input.size) input.copyOfRange(off, input.size) else ByteArray(0)
        val imports = scanImportStubs(code)
        val hints = scatterHints(code)
        val vmMain = findVmMain(code, imports)
        return Image(
            code = code,
            initialData = initialData,
            resources = resources,
            streams = streams,
            imports = imports,
            vmMainOffset = vmMain,
            dataSizeHint = hints.first,
            bssSizeHint = hints.second
        )
    }

    private fun scatterHints(code: ByteArray): Pair<Int, Int> {
        // Decode the immediate used by the first ADD r8,pc,#imm and read the four
        // scatter-loader table offsets. This is deliberately defensive: if a title
        // uses a different startup sequence the stream sizes remain authoritative.
        return runCatching {
            val first = u32(code, 0)
            val imm = decodeArmImmediate(first)
            val anchor = 8 + imm
            require(anchor >= 0 && anchor + 16 <= code.size)
            val copyStart = anchor + u32(code, anchor)
            val copyEnd = anchor + u32(code, anchor + 4)
            val zeroStart = anchor + u32(code, anchor + 8)
            val zeroEnd = anchor + u32(code, anchor + 12)
            var dataSize = 0
            var p = copyStart
            while (p + 12 <= copyEnd && p + 12 <= code.size) {
                val src = u32(code, p)
                val dst = u32(code, p + 4)
                val size = u32(code, p + 8)
                if (src != dst && size > dataSize && size < 64 * 1024 * 1024) dataSize = size
                p += 12
            }
            var bssEnd = 0
            p = zeroStart
            while (p + 8 <= zeroEnd && p + 8 <= code.size) {
                val dstFlags = u32(code, p)
                val size = u32(code, p + 4)
                val dst = dstFlags and -4
                if (size in 0 until (64 * 1024 * 1024)) bssEnd = maxOf(bssEnd, dst + size)
                p += 8
            }
            dataSize to maxOf(0, bssEnd - dataSize)
        }.getOrElse { 0 to 0 }
    }

    /** Find lazy MRE-binding veneers and associate each veneer with its ASCII vm_* name. */
    private fun scanImportStubs(code: ByteArray): List<ImportStub> {
        val out = linkedMapOf<String, ImportStub>()
        var off = 4
        while (off + 32 < code.size) {
            if (u32(code, off) == 0xe08f0000.toInt()) { // ADD r0,pc,r0
                val ldr = u32(code, off - 4)
                if ((ldr and 0xfffff000.toInt()) == 0xe59f0000.toInt()) {
                    val imm = ldr and 0xfff
                    val literal = off - 4 + 8 + imm
                    if (literal >= 0 && literal + 4 <= code.size) {
                        val rel = u32(code, literal)
                        val nameOff = off + 8 + rel
                        if (nameOff in code.indices) {
                            val name = readAsciiZ(code, nameOff, 128)
                            if (name == "strtoi" || name.startsWith("vm_")) {
                                var start = off - 4
                                var p = off - 4
                                while (p >= maxOf(0, off - 96)) {
                                    val w = u32(code, p)
                                    if ((w and 0xffff0000.toInt()) == 0xe92d0000.toInt()) {
                                        start = p
                                        break
                                    }
                                    p -= 4
                                }
                                out.putIfAbsent(name, ImportStub(name, start, nameOff))
                            }
                        }
                    }
                }
            }
            off += 4
        }
        return out.values.toList()
    }

    /**
     * vm_main heuristic for stripped raw VXP images.
     * Prefer a function that calls vm_reg_sysevt_callback and is itself reached from
     * the tiny entry/runtime wrapper near the beginning of the image.
     */
    private fun findVmMain(code: ByteArray, imports: List<ImportStub>): Int? {
        val sys = imports.firstOrNull { it.name == "vm_reg_sysevt_callback" }?.stubOffset ?: return null
        val key = imports.firstOrNull { it.name == "vm_reg_keyboard_callback" }?.stubOffset
        val pen = imports.firstOrNull { it.name == "vm_reg_pen_callback" }?.stubOffset
        val sysRefs = findBlRefs(code, sys)
        if (sysRefs.isEmpty()) return null
        data class Candidate(val start: Int, val score: Int, val earliestCaller: Int)
        val cs = sysRefs.map { ref ->
            val start = findFunctionStart(code, ref)
            var score = 10
            if (key != null && hasBlTo(code, start, minOf(code.size, start + 0x180), key)) score += 4
            if (pen != null && hasBlTo(code, start, minOf(code.size, start + 0x180), pen)) score += 2
            val callers = findBlRefs(code, start)
            val early = callers.filter { it < 0x4000 }.minOrNull() ?: Int.MAX_VALUE
            if (early != Int.MAX_VALUE) score += 20
            Candidate(start, score, early)
        }.distinctBy { it.start }
        return cs.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.earliestCaller }.thenBy { it.start }).firstOrNull()?.start
    }

    private fun findFunctionStart(code: ByteArray, at: Int): Int {
        var p = at and -4
        val floor = maxOf(0, p - 0x400)
        while (p >= floor) {
            val w = u32(code, p)
            if ((w and 0xffff0000.toInt()) == 0xe92d0000.toInt()) return p
            p -= 4
        }
        return at and -4
    }

    private fun hasBlTo(code: ByteArray, start: Int, end: Int, target: Int): Boolean {
        var p = start and -4
        while (p + 4 <= end) {
            if (decodeBlTarget(code, p) == target) return true
            p += 4
        }
        return false
    }

    private fun findBlRefs(code: ByteArray, target: Int): List<Int> {
        val out = mutableListOf<Int>()
        var p = 0
        while (p + 4 <= code.size) {
            if (decodeBlTarget(code, p) == target) out += p
            p += 4
        }
        return out
    }

    private fun decodeBlTarget(code: ByteArray, off: Int): Int? {
        val insn = u32(code, off)
        if ((insn and 0x0f000000) != 0x0b000000) return null
        var imm = insn and 0x00ffffff
        if ((imm and 0x00800000) != 0) imm = imm or 0xff000000.toInt()
        return off + 8 + (imm shl 2)
    }

    private fun inflateOne(input: ByteArray, offset: Int): ZStream? {
        if (!looksLikeZlib(input, offset)) return null
        val inflater = Inflater()
        inflater.setInput(input, offset, input.size - offset)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n > 0) out.write(buf, 0, n)
                else if (inflater.needsDictionary() || inflater.needsInput()) break
                else if (n == 0) break
            }
            if (!inflater.finished()) return null
            val consumed = inflater.totalIn
            return ZStream(offset, consumed, out.toByteArray())
        } catch (_: Throwable) {
            return null
        } finally {
            inflater.end()
        }
    }

    private fun looksLikeZlib(bytes: ByteArray, o: Int): Boolean {
        if (o < 0 || o + 2 > bytes.size) return false
        val cmf = bytes[o].toInt() and 0xff
        val flg = bytes[o + 1].toInt() and 0xff
        return (cmf and 0x0f) == 8 && (((cmf shl 8) + flg) % 31 == 0)
    }

    private fun readAsciiZ(bytes: ByteArray, offset: Int, max: Int): String {
        val endLimit = minOf(bytes.size, offset + max)
        var end = offset
        while (end < endLimit && bytes[end].toInt() != 0) {
            val b = bytes[end].toInt() and 0xff
            if (b !in 0x20..0x7e) return ""
            end++
        }
        if (end == offset) return ""
        return bytes.copyOfRange(offset, end).toString(Charsets.US_ASCII)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty()) return 0
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun decodeArmImmediate(insn: Int): Int {
        val imm8 = insn and 0xff
        val rot = ((insn ushr 8) and 0xf) * 2
        return Integer.rotateRight(imm8, rot)
    }

    private fun u32(bytes: ByteArray, o: Int): Int =
        (bytes[o].toInt() and 0xff) or
            ((bytes[o + 1].toInt() and 0xff) shl 8) or
            ((bytes[o + 2].toInt() and 0xff) shl 16) or
            ((bytes[o + 3].toInt() and 0xff) shl 24)
}
