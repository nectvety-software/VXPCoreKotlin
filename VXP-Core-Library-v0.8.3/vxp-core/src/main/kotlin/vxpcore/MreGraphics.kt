package vxpcore

import kotlin.math.max
import kotlin.math.min

/**
 * Minimal MediaTek MRE layer compositor.
 *
 * Guest-visible layer storage is RGB565 (2 bytes/pixel, little-endian) and lives
 * in a dedicated mapped graphics arena. The host framebuffer is also RGB565.
 */
class MreGraphics(
    private val memory: GuestMemory,
    val screenWidth: Int = 240,
    val screenHeight: Int = 320,
    graphicsBase: Int = GRAPHICS_BASE,
    graphicsSize: Int = GRAPHICS_SIZE,
    private val textRasterizer: TextRasterizer = BitmapTextRasterizer()
) {
    companion object {
        const val GRAPHICS_BASE = 0x60000000
        const val GRAPHICS_SIZE = 24 * 1024 * 1024
        const val VM_GRAPHIC_INVALID_LAYER = -1
    }

    data class Clip(var x: Int, var y: Int, var width: Int, var height: Int)

    data class Layer(
        val handle: Int,
        var x: Int,
        var y: Int,
        var width: Int,
        var height: Int,
        var transparentColor: Int,
        val bufferAddress: Int,
        var clip: Clip
    )

    data class Canvas(
        val handle: Int,
        val width: Int,
        val height: Int,
        val bufferAddress: Int,
        var transparentColor: Int = -1
    )

    private data class SurfaceRef(
        val bufferAddress: Int,
        val width: Int,
        val height: Int,
        val clip: Clip?
    )

    val frameBuffer = FrameBuffer(screenWidth, screenHeight)

    private val layers = linkedMapOf<Int, Layer>()
    private val canvases = linkedMapOf<Int, Canvas>()
    private val arenaBase = graphicsBase
    private val arenaSize = graphicsSize
    private var arenaTop = graphicsBase
    private var nextHandle = 1
    private var nextCanvasHandle = 0x1000

    var activeLayerHandle: Int = VM_GRAPHIC_INVALID_LAYER
        private set
    var currentColor: Int = 0xFFFF
    val text: TextRasterizer = textRasterizer
    var flushCount: Long = 0
        private set

    /** Called after each successful flush with an immutable RGB565 copy. */
    @Volatile var onFrame: ((FrameSnapshot) -> Unit)? = null

    init {
        memory.map(arenaBase, arenaSize, read = true, write = true, exec = false)
        frameBuffer.clear(0)
    }

    fun createLayer(x: Int, y: Int, width: Int, height: Int, transparentColor: Int): Int {
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) return VM_GRAPHIC_INVALID_LAYER
        val bytesLong = width.toLong() * height.toLong() * 2L
        if (bytesLong <= 0L || bytesLong > Int.MAX_VALUE) return VM_GRAPHIC_INVALID_LAYER
        val handle = allocateHandle()
        val buffer = allocateArena(bytesLong.toInt()) ?: return VM_GRAPHIC_INVALID_LAYER
        val layer = Layer(
            handle = handle,
            x = x,
            y = y,
            width = width,
            height = height,
            transparentColor = transparentColor,
            bufferAddress = buffer,
            clip = Clip(0, 0, width, height)
        )
        layers[handle] = layer
        if (activeLayerHandle == VM_GRAPHIC_INVALID_LAYER) activeLayerHandle = handle

        // MRE apps commonly use -1 as "no transparent color". Start fresh layers black.
        val initial = if (transparentColor >= 0) transparentColor and 0xFFFF else 0
        fillLayer(layer, initial)
        return handle
    }

    fun createLayerWithBuffer(x: Int, y: Int, width: Int, height: Int, transparentColor: Int, bufferAddress: Int): Int {
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) return VM_GRAPHIC_INVALID_LAYER
        val bytesLong = width.toLong() * height.toLong() * 2L
        if (bytesLong <= 0L || bytesLong > Int.MAX_VALUE || !memory.isMapped(bufferAddress, bytesLong.toInt())) {
            return VM_GRAPHIC_INVALID_LAYER
        }
        val handle = allocateHandle()
        layers[handle] = Layer(
            handle = handle,
            x = x, y = y, width = width, height = height,
            transparentColor = transparentColor,
            bufferAddress = bufferAddress,
            clip = Clip(0, 0, width, height)
        )
        if (activeLayerHandle == VM_GRAPHIC_INVALID_LAYER) activeLayerHandle = handle
        return handle
    }

    fun deleteLayer(handle: Int): Int {
        val removed = layers.remove(handle) ?: return -1
        if (activeLayerHandle == removed.handle) {
            activeLayerHandle = layers.keys.lastOrNull() ?: VM_GRAPHIC_INVALID_LAYER
        }
        return 0
    }

    fun getLayerBuffer(handle: Int): Int = layers[handle]?.bufferAddress ?: 0

    fun createCanvas(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) return 0
        val bytesLong = width.toLong() * height.toLong() * 2L
        if (bytesLong <= 0L || bytesLong > Int.MAX_VALUE) return 0
        val buffer = allocateArena(bytesLong.toInt()) ?: return 0
        val handle = allocateCanvasHandle()
        canvases[handle] = Canvas(handle, width, height, buffer)
        memory.writeBytes(buffer, ByteArray(bytesLong.toInt()))
        return handle
    }

    fun getCanvasBuffer(handle: Int): Int = canvases[handle]?.bufferAddress ?: 0

    /** Decode a PNG resource into a guest-visible RGB565 canvas. */
    fun loadImage(data: ByteArray): Int {
        val image = PngDecoder.decode(data) ?: return 0
        val handle = createCanvas(image.width, image.height)
        if (handle == 0) return 0
        val canvas = canvases[handle] ?: return 0
        var a = canvas.bufferAddress
        for (pixel in image.pixels) { memory.write16(a, pixel.toInt() and 0xffff); a += 2 }
        image.transparentColor565?.let { canvas.transparentColor = it }
        return handle
    }

    fun imageWidth(handle: Int): Int = canvases[handle]?.width ?: layers[handle]?.width ?: 0
    fun imageHeight(handle: Int): Int = canvases[handle]?.height ?: layers[handle]?.height ?: 0

    fun releaseCanvas(handle: Int): Int = if (canvases.remove(handle) != null) 0 else -1

    fun setCanvasTransparentColor(handle: Int, color: Int): Int {
        val canvas = canvases[handle] ?: return -1
        canvas.transparentColor = color and 0xFFFF
        return 0
    }

    /**
     * Draw into the surface owning [bufferAddress]. This matches raw MRE builds
     * that pass a layer/canvas pixel pointer as the first vm_graphic_fill_rect arg.
     */
    fun fillRectBuffer(bufferAddress: Int, x: Int, y: Int, width: Int, height: Int, color: Int = currentColor): Int {
        val surface = findSurfaceByBuffer(bufferAddress) ?: return -1
        val clip = surface.clip ?: Clip(0, 0, surface.width, surface.height)
        val x0 = max(max(0, x), clip.x)
        val y0 = max(max(0, y), clip.y)
        val x1 = min(min(surface.width, x + max(0, width)), clip.x + clip.width)
        val y1 = min(min(surface.height, y + max(0, height)), clip.y + clip.height)
        if (x0 >= x1 || y0 >= y1) return 0
        val c = color and 0xFFFF
        for (yy in y0 until y1) {
            var a = surface.bufferAddress + (yy * surface.width + x0) * 2
            repeat(x1 - x0) {
                memory.write16(a, c)
                a += 2
            }
        }
        return 0
    }

    fun activeLayer(handle: Int): Int {
        if (!layers.containsKey(handle)) return -1
        activeLayerHandle = handle
        return 0
    }

    fun setClip(x: Int, y: Int, width: Int, height: Int): Int {
        val layer = layers[activeLayerHandle] ?: return -1
        val x0 = x.coerceIn(0, layer.width)
        val y0 = y.coerceIn(0, layer.height)
        val x1 = (x.toLong() + max(0, width).toLong()).coerceIn(0L, layer.width.toLong()).toInt()
        val y1 = (y.toLong() + max(0, height).toLong()).coerceIn(0L, layer.height.toLong()).toInt()
        layer.clip = Clip(x0, y0, max(0, x1 - x0), max(0, y1 - y0))
        return 0
    }

    fun resetClip(): Int {
        val layer = layers[activeLayerHandle] ?: return -1
        layer.clip = Clip(0, 0, layer.width, layer.height)
        return 0
    }

    /**
     * Flush the supplied MRE layer handles in array order. Later layers are composited
     * on top of earlier layers. A negative transparentColor means opaque.
     */
    fun flushLayers(handles: IntArray): Int {
        if (handles.isEmpty()) return -1
        frameBuffer.clear(0)
        var any = false
        for (handle in handles) {
            val layer = layers[handle] ?: continue
            compose(layer)
            any = true
        }
        if (!any) return -1
        flushCount++
        onFrame?.invoke(frameBuffer.snapshot(flushCount))
        return 0
    }

    fun flushActiveLayer(): Int {
        val h = activeLayerHandle
        if (h == VM_GRAPHIC_INVALID_LAYER) return -1
        return flushLayers(intArrayOf(h))
    }


    /** Software implementation of the MRE vm_graphic_blt canvas path.
     * frameIndex is accepted for ABI compatibility; canvas buffers created by this
     * runtime are a single RGB565 frame, so frameIndex is ignored.
     */
    fun blt(
        dstBuffer: Int, xDest: Int, yDest: Int, srcBuffer: Int,
        xSrc: Int, ySrc: Int, width: Int, height: Int, frameIndex: Int
    ): Int {
        val dst = findSurfaceByBuffer(dstBuffer) ?: return -1
        val src = findSurfaceByBuffer(srcBuffer) ?: return -1
        if (width <= 0 || height <= 0) return 0

        val srcTransparent = canvases.values.firstOrNull { it.bufferAddress == srcBuffer }?.transparentColor
            ?: layers.values.firstOrNull { it.bufferAddress == srcBuffer }?.transparentColor
            ?: -1

        val dstClip = dst.clip ?: Clip(0, 0, dst.width, dst.height)
        for (row in 0 until height) {
            val sy = ySrc + row
            val dy = yDest + row
            if (sy !in 0 until src.height || dy !in 0 until dst.height) continue
            if (dy < dstClip.y || dy >= dstClip.y + dstClip.height) continue
            for (col in 0 until width) {
                val sx = xSrc + col
                val dx = xDest + col
                if (sx !in 0 until src.width || dx !in 0 until dst.width) continue
                if (dx < dstClip.x || dx >= dstClip.x + dstClip.width) continue
                val c = memory.read16(src.bufferAddress + (sy * src.width + sx) * 2)
                if (srcTransparent >= 0 && c == (srcTransparent and 0xFFFF)) continue
                memory.write16(dst.bufferAddress + (dy * dst.width + dx) * 2, c)
            }
        }
        @Suppress("UNUSED_VARIABLE") val ignoredFrameIndex = frameIndex
        return 0
    }

    fun setColor(rgb565: Int): Int {
        currentColor = rgb565 and 0xFFFF
        return 0
    }

    fun fillRect(x: Int, y: Int, width: Int, height: Int, color: Int = currentColor): Int {
        return fillRectOnLayer(activeLayerHandle, x, y, width, height, color)
    }

    fun fillRectOnLayer(handle: Int, x: Int, y: Int, width: Int, height: Int, color: Int = currentColor): Int {
        val layer = layers[handle] ?: return -1
        val clip = layer.clip
        val x0 = max(max(0, x), clip.x)
        val y0 = max(max(0, y), clip.y)
        val x1 = min(min(layer.width, x + max(0, width)), clip.x + clip.width)
        val y1 = min(min(layer.height, y + max(0, height)), clip.y + clip.height)
        if (x0 >= x1 || y0 >= y1) return 0
        val c = color and 0xFFFF
        for (yy in y0 until y1) {
            var a = layer.bufferAddress + (yy * layer.width + x0) * 2
            for (xx in x0 until x1) {
                memory.write16(a, c)
                a += 2
            }
        }
        return 0
    }

    fun drawLineBuffer(bufferAddress: Int, x0In: Int, y0In: Int, x1In: Int, y1In: Int, color: Int = currentColor): Int {
        val surface = findSurfaceByBuffer(bufferAddress) ?: return -1
        var x0 = x0In; var y0 = y0In
        val x1 = x1In; val y1 = y1In
        val dx = kotlin.math.abs(x1 - x0)
        val sx = if (x0 < x1) 1 else -1
        val dy = -kotlin.math.abs(y1 - y0)
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        val clip = surface.clip ?: Clip(0, 0, surface.width, surface.height)
        while (true) {
            if (x0 >= clip.x && y0 >= clip.y && x0 < clip.x + clip.width && y0 < clip.y + clip.height &&
                x0 in 0 until surface.width && y0 in 0 until surface.height) {
                memory.write16(surface.bufferAddress + (y0 * surface.width + x0) * 2, color and 0xFFFF)
            }
            if (x0 == x1 && y0 == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x0 += sx }
            if (e2 <= dx) { err += dx; y0 += sy }
        }
        return 0
    }

    fun drawLine(x0In: Int, y0In: Int, x1In: Int, y1In: Int, color: Int = currentColor): Int {
        val layer = layers[activeLayerHandle] ?: return -1
        var x0 = x0In; var y0 = y0In
        val x1 = x1In; val y1 = y1In
        val dx = kotlin.math.abs(x1 - x0)
        val sx = if (x0 < x1) 1 else -1
        val dy = -kotlin.math.abs(y1 - y0)
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            if (x0 >= layer.clip.x && y0 >= layer.clip.y && x0 < layer.clip.x + layer.clip.width && y0 < layer.clip.y + layer.clip.height && x0 in 0 until layer.width && y0 in 0 until layer.height) {
                memory.write16(layer.bufferAddress + (y0 * layer.width + x0) * 2, color and 0xFFFF)
            }
            if (x0 == x1 && y0 == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x0 += sx }
            if (e2 <= dx) { err += dx; y0 += sy }
        }
        return 0
    }

    fun drawRect(x: Int, y: Int, width: Int, height: Int, color: Int = currentColor): Int {
        return drawRectOnLayer(activeLayerHandle, x, y, width, height, color)
    }

    fun drawRectOnLayer(handle: Int, x: Int, y: Int, width: Int, height: Int, color: Int = currentColor): Int {
        val previous = activeLayerHandle
        if (!layers.containsKey(handle)) return -1
        activeLayerHandle = handle
        if (width <= 0 || height <= 0) return 0
        try {
            drawLine(x, y, x + width - 1, y, color)
            drawLine(x, y, x, y + height - 1, color)
            drawLine(x + width - 1, y, x + width - 1, y + height - 1, color)
            drawLine(x, y + height - 1, x + width - 1, y + height - 1, color)
        } finally {
            activeLayerHandle = previous
        }
        return 0
    }


    fun setFont(fontId: Int): Int {
        text.setFont(fontId)
        return 0
    }

    fun characterWidth(ch: Int): Int = text.charWidth(ch and 0xFFFF)
    fun characterHeight(): Int = text.height()
    fun stringWidth(value: String): Int = text.measure(value).first
    fun stringHeight(value: String): Int = if (value.isEmpty()) text.height() else text.measure(value).second

    /** Render UCS2 text to a layer/canvas RGB565 surface using the current MRE color. */
    fun drawTextBuffer(
        bufferAddress: Int,
        x: Int,
        y: Int,
        value: String,
        maxWidth: Int = Int.MAX_VALUE,
        color: Int = currentColor
    ): Int {
        val surface = findSurfaceByBuffer(bufferAddress) ?: return -1
        return drawTextSurface(surface, x, y, value, maxWidth, color)
    }

    fun drawTextLayer(
        handle: Int,
        x: Int,
        y: Int,
        value: String,
        maxWidth: Int = Int.MAX_VALUE,
        color: Int = currentColor
    ): Int {
        val layer = layers[handle] ?: return -1
        return drawTextSurface(SurfaceRef(layer.bufferAddress, layer.width, layer.height, layer.clip), x, y, value, maxWidth, color)
    }

    private fun drawTextSurface(
        surface: SurfaceRef,
        x: Int,
        y: Int,
        value: String,
        maxWidth: Int,
        color: Int
    ): Int {
        if (value.isEmpty()) return 0
        val clip = surface.clip ?: Clip(0, 0, surface.width, surface.height)
        val run = text.rasterize(value, maxWidth)
        val rgb565 = color and 0xFFFF
        for (gy in 0 until run.height) {
            val dy = y + gy
            if (dy !in 0 until surface.height || dy < clip.y || dy >= clip.y + clip.height) continue
            for (gx in 0 until run.width) {
                val alpha = run.alpha[gy * run.width + gx]
                if (alpha < 96) continue
                val dx = x + gx
                if (dx !in 0 until surface.width || dx < clip.x || dx >= clip.x + clip.width) continue
                val addr = surface.bufferAddress + (dy * surface.width + dx) * 2
                if (alpha >= 224) {
                    memory.write16(addr, rgb565)
                } else {
                    // Cheap RGB565 alpha blend, enough for vector-font compatibility.
                    val dst = memory.read16(addr)
                    memory.write16(addr, blend565(dst, rgb565, alpha))
                }
            }
        }
        return 0
    }

    private fun blend565(dst: Int, src: Int, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255)
        val sr = (src ushr 11) and 0x1f
        val sg = (src ushr 5) and 0x3f
        val sb = src and 0x1f
        val dr = (dst ushr 11) and 0x1f
        val dg = (dst ushr 5) and 0x3f
        val db = dst and 0x1f
        val r = (sr * a + dr * (255 - a) + 127) / 255
        val g = (sg * a + dg * (255 - a) + 127) / 255
        val b = (sb * a + db * (255 - a) + 127) / 255
        return (r shl 11) or (g shl 5) or b
    }

    fun layerCount(): Int = layers.size
    fun canvasCount(): Int = canvases.size
    fun layer(handle: Int): Layer? = layers[handle]
    fun canvas(handle: Int): Canvas? = canvases[handle]
    fun handles(): IntArray = layers.keys.toIntArray()

    private fun compose(layer: Layer) {
        val raw = memory.readBytes(layer.bufferAddress, layer.width * layer.height * 2)
        val transparent = layer.transparentColor
        val dstX0 = max(0, layer.x)
        val dstY0 = max(0, layer.y)
        val dstX1 = min(screenWidth, layer.x + layer.width)
        val dstY1 = min(screenHeight, layer.y + layer.height)
        if (dstX0 >= dstX1 || dstY0 >= dstY1) return

        for (dy in dstY0 until dstY1) {
            val sy = dy - layer.y
            for (dx in dstX0 until dstX1) {
                val sx = dx - layer.x
                val i = (sy * layer.width + sx) * 2
                val color = (raw[i].toInt() and 0xFF) or ((raw[i + 1].toInt() and 0xFF) shl 8)
                if (transparent >= 0 && color == (transparent and 0xFFFF)) continue
                frameBuffer.setPixel(dx, dy, color)
            }
        }
    }

    private fun fillLayer(layer: Layer, color: Int) {
        val lo = (color and 0xFF).toByte()
        val hi = ((color ushr 8) and 0xFF).toByte()
        val bytes = ByteArray(layer.width * layer.height * 2)
        var i = 0
        while (i < bytes.size) {
            bytes[i] = lo
            bytes[i + 1] = hi
            i += 2
        }
        memory.writeBytes(layer.bufferAddress, bytes)
    }

    private fun findSurfaceByBuffer(address: Int): SurfaceRef? {
        layers.values.firstOrNull { it.bufferAddress == address }?.let {
            return SurfaceRef(it.bufferAddress, it.width, it.height, it.clip)
        }
        canvases.values.firstOrNull { it.bufferAddress == address }?.let {
            return SurfaceRef(it.bufferAddress, it.width, it.height, null)
        }
        return null
    }

    private fun allocateArena(bytes: Int): Int? {
        if (bytes <= 0) return null
        val alignedLong = (bytes.toLong() + 7L) and -8L
        val end = arenaTop.toUInt().toLong() + alignedLong
        val limit = arenaBase.toUInt().toLong() + arenaSize.toLong()
        if (alignedLong <= 0 || end > limit) return null
        val out = arenaTop
        arenaTop = (arenaTop.toUInt().toLong() + alignedLong).toInt()
        return out
    }

    private fun allocateHandle(): Int {
        while (nextHandle == 0 || nextHandle == VM_GRAPHIC_INVALID_LAYER || layers.containsKey(nextHandle)) nextHandle++
        return nextHandle++
    }

    private fun allocateCanvasHandle(): Int {
        while (nextCanvasHandle == 0 || canvases.containsKey(nextCanvasHandle)) nextCanvasHandle++
        return nextCanvasHandle++
    }
}
