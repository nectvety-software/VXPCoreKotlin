package vxpcore

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import kotlin.math.abs

/** Small host-neutral PNG decoder for MRE image resources. Supports non-interlaced PNG. */
object PngDecoder {
    data class Image(
        val width: Int,
        val height: Int,
        val pixels: ShortArray,
        val transparentColor565: Int? = null
    )

    fun decode(bytes: ByteArray): Image? = runCatching { decodeOrThrow(bytes) }.getOrNull()

    private fun decodeOrThrow(bytes: ByteArray): Image {
        require(bytes.size >= 33 && bytes.copyOfRange(0, 8).contentEquals(SIGNATURE)) { "Not PNG" }
        var p = 8
        var width = 0
        var height = 0
        var bitDepth = 0
        var colorType = -1
        var interlace = 0
        var palette: IntArray? = null
        var alpha: ByteArray? = null
        val idat = ByteArrayOutputStream()
        while (p + 12 <= bytes.size) {
            val len = be32(bytes, p); p += 4
            require(len >= 0 && p + 4 + len + 4 <= bytes.size) { "Bad PNG chunk" }
            val type = String(bytes, p, 4, Charsets.US_ASCII); p += 4
            val dataOff = p
            when (type) {
                "IHDR" -> {
                    require(len == 13)
                    width = be32(bytes, dataOff)
                    height = be32(bytes, dataOff + 4)
                    bitDepth = bytes[dataOff + 8].toInt() and 0xff
                    colorType = bytes[dataOff + 9].toInt() and 0xff
                    require((bytes[dataOff + 10].toInt() and 0xff) == 0)
                    require((bytes[dataOff + 11].toInt() and 0xff) == 0)
                    interlace = bytes[dataOff + 12].toInt() and 0xff
                }
                "PLTE" -> {
                    require(len % 3 == 0)
                    palette = IntArray(len / 3) { i ->
                        val r = bytes[dataOff + i * 3].toInt() and 0xff
                        val g = bytes[dataOff + i * 3 + 1].toInt() and 0xff
                        val b = bytes[dataOff + i * 3 + 2].toInt() and 0xff
                        rgb565(r, g, b)
                    }
                }
                "tRNS" -> alpha = bytes.copyOfRange(dataOff, dataOff + len)
                "IDAT" -> idat.write(bytes, dataOff, len)
                "IEND" -> break
            }
            p += len + 4 // payload + CRC
            if (type == "IEND") break
        }
        require(width in 1..4096 && height in 1..4096)
        require(interlace == 0) { "Interlaced PNG not supported" }
        val channels = when (colorType) {
            0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; 6 -> 4
            else -> error("PNG color type $colorType")
        }
        require(bitDepth in when (colorType) {
            3, 0 -> setOf(1, 2, 4, 8)
            else -> setOf(8)
        }) { "PNG bit depth $bitDepth type=$colorType" }
        if (colorType == 3) require(palette != null) { "Indexed PNG missing PLTE" }

        val rowBytes = ((width * channels * bitDepth) + 7) / 8
        val bpp = maxOf(1, ((channels * bitDepth) + 7) / 8)
        val expected = height * (rowBytes + 1)
        val packed = inflate(idat.toByteArray(), expected)
        require(packed.size >= expected) { "PNG data short ${packed.size}/$expected" }
        val rows = ByteArray(height * rowBytes)
        var src = 0
        var prevOff = -1
        for (y in 0 until height) {
            val filter = packed[src++].toInt() and 0xff
            val rowOff = y * rowBytes
            for (x in 0 until rowBytes) {
                val raw = packed[src++].toInt() and 0xff
                val a = if (x >= bpp) rows[rowOff + x - bpp].toInt() and 0xff else 0
                val b = if (prevOff >= 0) rows[prevOff + x].toInt() and 0xff else 0
                val c = if (prevOff >= 0 && x >= bpp) rows[prevOff + x - bpp].toInt() and 0xff else 0
                val v = when (filter) {
                    0 -> raw
                    1 -> (raw + a) and 0xff
                    2 -> (raw + b) and 0xff
                    3 -> (raw + ((a + b) ushr 1)) and 0xff
                    4 -> (raw + paeth(a, b, c)) and 0xff
                    else -> error("PNG filter $filter")
                }
                rows[rowOff + x] = v.toByte()
            }
            prevOff = rowOff
        }

        val out = ShortArray(width * height)
        var transparent: Int? = null
        for (y in 0 until height) {
            val ro = y * rowBytes
            for (x in 0 until width) {
                val pi = y * width + x
                val color: Int
                var opaque = true
                when (colorType) {
                    3 -> {
                        val index = packedSample(rows, ro, x, bitDepth)
                        val pal = palette!!
                        color = pal.getOrElse(index) { 0 }
                        opaque = (alpha?.getOrNull(index)?.toInt()?.and(0xff) ?: 255) >= 128
                    }
                    0 -> {
                        val g = sampleTo8(packedSample(rows, ro, x, bitDepth), bitDepth)
                        color = rgb565(g, g, g)
                    }
                    2 -> {
                        val o = ro + x * 3
                        color = rgb565(rows[o].u8(), rows[o + 1].u8(), rows[o + 2].u8())
                    }
                    4 -> {
                        val o = ro + x * 2
                        val g = rows[o].u8(); opaque = rows[o + 1].u8() >= 128
                        color = rgb565(g, g, g)
                    }
                    6 -> {
                        val o = ro + x * 4
                        color = rgb565(rows[o].u8(), rows[o + 1].u8(), rows[o + 2].u8())
                        opaque = rows[o + 3].u8() >= 128
                    }
                    else -> error("unreachable")
                }
                if (opaque) out[pi] = color.toShort() else {
                    val key = transparent ?: chooseTransparentKey(palette)
                    transparent = key
                    out[pi] = key.toShort()
                }
            }
        }
        return Image(width, height, out, transparent)
    }

    private fun chooseTransparentKey(palette: IntArray?): Int {
        val used = palette?.toHashSet() ?: emptySet()
        for (v in intArrayOf(0xF81F, 0x07E0, 0x001F, 0x0000, 0xFFFF)) if (v !in used) return v
        return 0xF81F
    }

    private fun packedSample(row: ByteArray, rowOff: Int, x: Int, bits: Int): Int {
        if (bits == 8) return row[rowOff + x].u8()
        val per = 8 / bits
        val b = row[rowOff + x / per].u8()
        val shift = (per - 1 - (x % per)) * bits
        return (b ushr shift) and ((1 shl bits) - 1)
    }

    private fun sampleTo8(v: Int, bits: Int): Int = when (bits) {
        1 -> if (v == 0) 0 else 255
        2 -> v * 85
        4 -> v * 17
        else -> v
    }

    private fun inflate(data: ByteArray, expected: Int): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream(expected.coerceAtLeast(1024))
        val buf = ByteArray(32 * 1024)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n > 0) out.write(buf, 0, n)
                else if (inflater.needsInput() || inflater.needsDictionary()) break
                else if (n == 0) break
            }
        } finally { inflater.end() }
        return out.toByteArray()
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a); val pb = abs(p - b); val pc = abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun rgb565(r: Int, g: Int, b: Int): Int =
        ((r and 0xF8) shl 8) or ((g and 0xFC) shl 3) or ((b and 0xF8) ushr 3)

    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or
            ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

    private fun Byte.u8(): Int = toInt() and 0xff
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
}
