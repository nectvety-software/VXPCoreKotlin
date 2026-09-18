package vxpcore

/** Immutable frame handed to host UIs. Pixels are RGB565. */
data class FrameSnapshot(
    val width: Int,
    val height: Int,
    val pixels: ShortArray,
    val serial: Long
)

/** Host-neutral RGB565 framebuffer. No AWT/Android dependency. */
class FrameBuffer(
    val width: Int = 240,
    val height: Int = 320
) {
    private val pixels = ShortArray(width * height)

    fun clear(rgb565: Int = 0) {
        java.util.Arrays.fill(pixels, (rgb565 and 0xffff).toShort())
    }

    fun setPixel(x: Int, y: Int, rgb565: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        pixels[y * width + x] = (rgb565 and 0xffff).toShort()
    }

    fun getPixel565(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height)
        return pixels[y * width + x].toInt() and 0xffff
    }

    fun copy565(): ShortArray = pixels.copyOf()

    fun copyInto(dst: ShortArray) {
        require(dst.size >= pixels.size)
        pixels.copyInto(dst, 0, 0, pixels.size)
    }

    fun snapshot(serial: Long): FrameSnapshot =
        FrameSnapshot(width, height, pixels.copyOf(), serial)

    companion object {
        fun rgb565ToArgb(c: Int): Int {
            val r5 = (c ushr 11) and 0x1f
            val g6 = (c ushr 5) and 0x3f
            val b5 = c and 0x1f
            val r = (r5 shl 3) or (r5 ushr 2)
            val g = (g6 shl 2) or (g6 ushr 4)
            val b = (b5 shl 3) or (b5 ushr 2)
            return (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
