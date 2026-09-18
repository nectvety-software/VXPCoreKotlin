package vxpcore.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import vxpcore.TextRasterizer
import kotlin.math.ceil

/** Android system-font implementation for MRE vm_graphic_textout* APIs. */
class AndroidTextRasterizer : TextRasterizer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT
    }
    private var fontId = 0

    override fun setFont(id: Int) {
        fontId = id and 0xff
        paint.textSize = fontSize(fontId).toFloat()
    }

    override fun currentFontId(): Int = fontId

    override fun measure(text: String): Pair<Int, Int> {
        ensurePaint()
        val fm = paint.fontMetricsInt
        return ceil(paint.measureText(text)).toInt() to (fm.descent - fm.ascent)
    }

    override fun charWidth(ch: Int): Int = measure(ch.toChar().toString()).first.coerceAtLeast(1)

    override fun height(): Int {
        ensurePaint()
        val fm = paint.fontMetricsInt
        return (fm.descent - fm.ascent).coerceAtLeast(1)
    }

    override fun rasterize(text: String, maxWidth: Int): TextRasterizer.GlyphRun {
        ensurePaint()
        val fm = paint.fontMetricsInt
        val fullWidth = ceil(paint.measureText(text)).toInt().coerceAtLeast(1)
        val width = minOf(fullWidth, maxWidth.coerceAtLeast(1))
        val height = (fm.descent - fm.ascent).coerceAtLeast(1)
        val baseline = -fm.ascent
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        canvas.drawText(text, 0f, baseline.toFloat(), paint)
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        bitmap.recycle()
        val alpha = IntArray(argb.size)
        for (i in argb.indices) alpha[i] = (argb[i] ushr 24) and 0xff
        return TextRasterizer.GlyphRun(width, height, baseline, alpha)
    }

    private fun ensurePaint() {
        if (paint.textSize <= 0f) paint.textSize = fontSize(fontId).toFloat()
    }

    private fun fontSize(id: Int): Int = when (id) {
        0 -> 12
        1 -> 14
        2 -> 16
        3 -> 18
        4 -> 20
        5 -> 22
        6 -> 24
        7 -> 10
        in 8..32 -> id
        else -> 14
    }
}
