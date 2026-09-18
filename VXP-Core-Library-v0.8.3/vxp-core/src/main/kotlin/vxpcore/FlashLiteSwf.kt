package vxpcore

import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream
import kotlin.math.ceil

/** Minimal SWF/Flash Lite probe used to identify SWF-based .vxp packages.
 * It intentionally implements only enough AVM1 to execute simple startup actions
 * such as SetVariable, Stop, Push and Flash Lite FSCommand2.
 */
object FlashLiteSwf {
    data class Info(
        val signature: String,
        val version: Int,
        val fileLength: Long,
        val width: Int,
        val height: Int,
        val frameRate: Double,
        val frameCount: Int,
        val tagCounts: Map<Int, Int>,
        val fsCommand2Count: Int,
        val rootDoActionCount: Int
    )

    data class StartupResult(
        val reachedFrame: Int,
        val stopped: Boolean,
        val variables: Map<String, String>,
        val fsCommands: List<FsCommand>
    )

    data class FsCommand(val command: String, val args: List<String>)

    private data class Tag(val code: Int, val data: ByteArray, val frame: Int)

    fun isSwf(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val s = bytes.copyOfRange(0, 3).toString(Charsets.US_ASCII)
        return s == "FWS" || s == "CWS" || s == "ZWS"
    }

    fun inspect(input: ByteArray): Info {
        val bytes = normalize(input)
        require(bytes.size >= 12) { "Truncated SWF" }
        val version = bytes[3].toInt() and 0xff
        val fileLength = u32(bytes, 4)
        val rect = readRect(bytes, 8)
        var p = rect.next
        val rateRaw = u16(bytes, p); p += 2
        val frameRate = ((rateRaw ushr 8) and 0xff) + (rateRaw and 0xff) / 256.0
        val frameCount = u16(bytes, p); p += 2
        val tags = readTags(bytes, p)
        val counts = linkedMapOf<Int, Int>()
        var fs2 = 0
        var actions = 0
        for (t in tags) {
            counts[t.code] = (counts[t.code] ?: 0) + 1
            if (t.code == 12) {
                actions++
                fs2 += countAction(t.data, 0x2d)
            }
        }
        return Info(
            signature = input.copyOfRange(0, 3).toString(Charsets.US_ASCII),
            version = version,
            fileLength = fileLength,
            width = (rect.xMax - rect.xMin) / 20,
            height = (rect.yMax - rect.yMin) / 20,
            frameRate = frameRate,
            frameCount = frameCount,
            tagCounts = counts,
            fsCommand2Count = fs2,
            rootDoActionCount = actions
        )
    }

    /** Execute root timeline actions from frame 0 until the first Stop action.
     * This is a probe, not a complete AVM1 implementation.
     */
    fun runStartup(input: ByteArray, maxFrames: Int = 64): StartupResult {
        val bytes = normalize(input)
        val rect = readRect(bytes, 8)
        var p = rect.next + 4 // frame rate + frame count
        val tags = readTags(bytes, p)
        val vars = linkedMapOf<String, String>()
        val fsCommands = mutableListOf<FsCommand>()
        var stopped = false
        var reached = 0
        for (tag in tags) {
            val currentFrame = tag.frame
            if (currentFrame >= maxFrames || stopped) break
            if (tag.code == 12) {
                val r = executeActions(tag.data, vars, fsCommands)
                if (r) stopped = true
            }
            if (tag.code == 1) reached = maxOf(reached, currentFrame)
        }
        return StartupResult(reached, stopped, vars.toMap(), fsCommands.toList())
    }

    private fun executeActions(
        code: ByteArray,
        vars: MutableMap<String, String>,
        fsCommands: MutableList<FsCommand>
    ): Boolean {
        val stack = mutableListOf<Any?>()
        var p = 0
        var stopped = false
        while (p < code.size) {
            val op = code[p++].toInt() and 0xff
            if (op == 0) break
            var payload = ByteArray(0)
            if (op >= 0x80) {
                if (p + 2 > code.size) break
                val len = u16(code, p); p += 2
                if (p + len > code.size) break
                payload = code.copyOfRange(p, p + len); p += len
            }
            when (op) {
                0x07 -> stopped = true // Stop
                0x17 -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) // Pop
                0x1d -> { // SetVariable: name then value are popped
                    val value = popString(stack)
                    val name = popString(stack)
                    vars[name] = value
                }
                0x96 -> parsePush(payload).forEach { stack.add(it) }
                0x2d -> { // Flash Lite ActionFSCommand2
                    val count = popString(stack).toIntOrNull() ?: 0
                    if (count > 0) {
                        val command = popString(stack)
                        val args = mutableListOf<String>()
                        repeat((count - 1).coerceAtLeast(0)) { args += popString(stack) }
                        fsCommands += FsCommand(command, args)
                        stack.add("0") // supported in probe
                    } else {
                        stack.add("-1")
                    }
                }
                // Other AVM1 operations are deliberately ignored by the startup probe.
            }
        }
        return stopped
    }

    private fun parsePush(data: ByteArray): List<Any?> {
        val out = mutableListOf<Any?>()
        var p = 0
        while (p < data.size) {
            when (data[p++].toInt() and 0xff) {
                0 -> {
                    val z = data.indexOf(0, p)
                    val end = if (z < 0) data.size else z
                    out += data.copyOfRange(p, end).toString(Charsets.ISO_8859_1)
                    p = (end + 1).coerceAtMost(data.size)
                }
                1 -> { // float
                    if (p + 4 > data.size) break
                    val bits = u32(data, p).toInt(); p += 4
                    out += Float.fromBits(bits)
                }
                2 -> out += null
                3 -> out += null
                4 -> { if (p < data.size) out += (data[p++].toInt() and 0xff) }
                5 -> { if (p < data.size) out += ((data[p++].toInt() and 0xff) != 0) }
                7 -> { if (p + 4 > data.size) break; out += u32(data, p).toInt(); p += 4 }
                8 -> { if (p < data.size) out += (data[p++].toInt() and 0xff) }
                9 -> { if (p + 2 > data.size) break; out += u16(data, p); p += 2 }
                else -> break
            }
        }
        return out
    }

    private fun popString(stack: MutableList<Any?>): String =
        if (stack.isEmpty()) "" else when (val v = stack.removeAt(stack.lastIndex)) {
            null -> ""
            is Boolean -> if (v) "1" else "0"
            else -> v.toString()
        }

    private fun countAction(code: ByteArray, target: Int): Int {
        var p = 0
        var n = 0
        while (p < code.size) {
            val op = code[p++].toInt() and 0xff
            if (op == 0) break
            if (op == target) n++
            if (op >= 0x80) {
                if (p + 2 > code.size) break
                val len = u16(code, p); p += 2 + len
            }
        }
        return n
    }

    private fun normalize(input: ByteArray): ByteArray {
        require(input.size >= 8) { "Truncated SWF" }
        val sig = input.copyOfRange(0, 3).toString(Charsets.US_ASCII)
        if (sig == "FWS") return input
        if (sig == "CWS") {
            val head = input.copyOfRange(0, 8)
            head[0] = 'F'.code.toByte()
            val body = InflaterInputStream(ByteArrayInputStream(input, 8, input.size - 8)).use { it.readBytes() }
            return head + body
        }
        error("ZWS/LZMA SWF is detected but not implemented yet")
    }

    private data class Rect(val xMin: Int, val xMax: Int, val yMin: Int, val yMax: Int, val next: Int)

    private fun readRect(bytes: ByteArray, off: Int): Rect {
        var bit = off * 8
        fun readBits(n: Int): Int {
            var v = 0
            repeat(n) {
                val b = bytes[bit ushr 3].toInt() and 0xff
                v = (v shl 1) or ((b ushr (7 - (bit and 7))) and 1)
                bit++
            }
            return v
        }
        fun signed(v: Int, n: Int): Int = if (n > 0 && (v and (1 shl (n - 1))) != 0) v - (1 shl n) else v
        val n = readBits(5)
        val x0 = signed(readBits(n), n)
        val x1 = signed(readBits(n), n)
        val y0 = signed(readBits(n), n)
        val y1 = signed(readBits(n), n)
        val next = ceil(bit / 8.0).toInt()
        return Rect(x0, x1, y0, y1, next)
    }

    private fun readTags(bytes: ByteArray, start: Int): List<Tag> {
        val out = mutableListOf<Tag>()
        var p = start
        var frame = 0
        while (p + 2 <= bytes.size) {
            val rec = u16(bytes, p); p += 2
            val code = rec ushr 6
            var len = rec and 0x3f
            if (len == 0x3f) {
                if (p + 4 > bytes.size) break
                len = u32(bytes, p).toInt(); p += 4
            }
            if (len < 0 || p + len > bytes.size) break
            val payload = bytes.copyOfRange(p, p + len)
            out += Tag(code, payload, frame)
            p += len
            if (code == 1) frame++
            if (code == 0) break
        }
        return out
    }

    private fun ByteArray.indexOf(value: Int, start: Int): Int {
        for (i in start until size) if ((this[i].toInt() and 0xff) == value) return i
        return -1
    }

    private fun u16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

    private fun u32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xff) or
            ((b[o + 1].toLong() and 0xff) shl 8) or
            ((b[o + 2].toLong() and 0xff) shl 16) or
            ((b[o + 3].toLong() and 0xff) shl 24)
}
