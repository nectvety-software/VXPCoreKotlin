package vxpcore.android

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import vxpcore.FrameSnapshot

/** Reuses one RGB_565 Bitmap; it is not a View/Compose component. */
class Rgb565BitmapAdapter {
    private var bitmap: Bitmap? = null
    private var buffer: ByteBuffer? = null

    @Synchronized
    fun update(frame: FrameSnapshot): Bitmap {
        val required = frame.width * frame.height * 2
        var out = bitmap
        if (out == null || out.width != frame.width || out.height != frame.height) {
            out?.recycle()
            out = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.RGB_565)
            bitmap = out
            buffer = ByteBuffer.allocateDirect(required).order(ByteOrder.nativeOrder())
        }
        val bytes = if (buffer == null || buffer!!.capacity() < required) {
            ByteBuffer.allocateDirect(required).order(ByteOrder.nativeOrder()).also { buffer = it }
        } else {
            buffer!!
        }
        bytes.clear()
        bytes.asShortBuffer().put(frame.pixels)
        bytes.position(0)
        out.copyPixelsFromBuffer(bytes)
        return out
    }

    @Synchronized
    fun close() {
        bitmap?.recycle()
        bitmap = null
        buffer = null
    }
}
