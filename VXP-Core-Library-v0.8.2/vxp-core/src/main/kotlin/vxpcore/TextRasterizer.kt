package vxpcore

/** Host-neutral text raster contract used by the MRE text APIs. */
interface TextRasterizer {
    data class GlyphRun(
        val width: Int,
        val height: Int,
        val baseline: Int,
        val alpha: IntArray
    )

    fun setFont(id: Int)
    fun currentFontId(): Int
    fun measure(text: String): Pair<Int, Int>
    fun charWidth(ch: Int): Int
    fun height(): Int
    fun rasterize(text: String, maxWidth: Int = Int.MAX_VALUE): GlyphRun
}

/**
 * Pure Kotlin fallback rasterizer. It deliberately favors portability over beauty:
 * every printable character is represented by a deterministic 5x7 cell. Android
 * injects AndroidTextRasterizer for real system-font metrics/rasterization.
 */
class BitmapTextRasterizer : TextRasterizer {
    private var fontId: Int = 0

    override fun setFont(id: Int) { fontId = id and 0xff }
    override fun currentFontId(): Int = fontId

    private fun scale(): Int = when (fontId) {
        0, 7 -> 1
        1, 2, 3 -> 2
        4, 5, 6 -> 3
        else -> ((fontId.coerceIn(8, 32) + 7) / 8).coerceIn(1, 4)
    }

    override fun measure(text: String): Pair<Int, Int> {
        val s = scale()
        if (text.isEmpty()) return 0 to (8 * s)
        return (text.length * 6 * s) to (8 * s)
    }

    override fun charWidth(ch: Int): Int = 6 * scale()
    override fun height(): Int = 8 * scale()

    override fun rasterize(text: String, maxWidth: Int): TextRasterizer.GlyphRun {
        val s = scale()
        val fullW = (text.length * 6 * s).coerceAtLeast(1)
        val w = minOf(fullW, maxWidth.coerceAtLeast(1))
        val h = 8 * s
        val alpha = IntArray(w * h)
        var cursor = 0
        for (ch in text) {
            if (cursor >= w) break
            val rows = glyph(ch)
            for (gy in 0 until 7) {
                val bits = rows[gy]
                for (gx in 0 until 5) {
                    if ((bits and (1 shl (4 - gx))) == 0) continue
                    for (sy in 0 until s) for (sx in 0 until s) {
                        val x = cursor + gx * s + sx
                        val y = gy * s + sy
                        if (x in 0 until w && y in 0 until h) alpha[y * w + x] = 255
                    }
                }
            }
            cursor += 6 * s
        }
        return TextRasterizer.GlyphRun(w, h, 7 * s, alpha)
    }

    /** Small 5x7 ASCII subset. Unknown glyphs render as a box, never as garbage. */
    private fun glyph(chIn: Char): IntArray {
        val ch = chIn.uppercaseChar()
        return GLYPHS[ch] ?: BOX
    }

    companion object {
        private val BOX = intArrayOf(0b11111,0b10001,0b10001,0b10001,0b10001,0b10001,0b11111)
        private fun g(vararg rows: Int) = rows
        private val GLYPHS: Map<Char, IntArray> = mapOf(
            ' ' to g(0,0,0,0,0,0,0),
            '-' to g(0,0,0,31,0,0,0),
            '_' to g(0,0,0,0,0,0,31),
            '.' to g(0,0,0,0,0,12,12),
            ':' to g(0,12,12,0,12,12,0),
            '/' to g(1,2,4,8,16,0,0),
            '\\' to g(16,8,4,2,1,0,0),
            '0' to g(14,17,19,21,25,17,14),
            '1' to g(4,12,4,4,4,4,14),
            '2' to g(14,17,1,2,4,8,31),
            '3' to g(30,1,1,14,1,1,30),
            '4' to g(2,6,10,18,31,2,2),
            '5' to g(31,16,16,30,1,1,30),
            '6' to g(14,16,16,30,17,17,14),
            '7' to g(31,1,2,4,8,8,8),
            '8' to g(14,17,17,14,17,17,14),
            '9' to g(14,17,17,15,1,1,14),
            'A' to g(14,17,17,31,17,17,17),
            'B' to g(30,17,17,30,17,17,30),
            'C' to g(14,17,16,16,16,17,14),
            'D' to g(30,17,17,17,17,17,30),
            'E' to g(31,16,16,30,16,16,31),
            'F' to g(31,16,16,30,16,16,16),
            'G' to g(14,17,16,23,17,17,15),
            'H' to g(17,17,17,31,17,17,17),
            'I' to g(14,4,4,4,4,4,14),
            'J' to g(7,2,2,2,18,18,12),
            'K' to g(17,18,20,24,20,18,17),
            'L' to g(16,16,16,16,16,16,31),
            'M' to g(17,27,21,21,17,17,17),
            'N' to g(17,25,21,19,17,17,17),
            'O' to g(14,17,17,17,17,17,14),
            'P' to g(30,17,17,30,16,16,16),
            'Q' to g(14,17,17,17,21,18,13),
            'R' to g(30,17,17,30,20,18,17),
            'S' to g(15,16,16,14,1,1,30),
            'T' to g(31,4,4,4,4,4,4),
            'U' to g(17,17,17,17,17,17,14),
            'V' to g(17,17,17,17,17,10,4),
            'W' to g(17,17,17,17,21,27,17),
            'X' to g(17,17,10,4,10,17,17),
            'Y' to g(17,17,10,4,4,4,4),
            'Z' to g(31,1,2,4,8,16,31)
        )
    }
}
