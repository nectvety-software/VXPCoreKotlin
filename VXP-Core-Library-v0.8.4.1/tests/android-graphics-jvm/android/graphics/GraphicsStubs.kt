package android.graphics

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

object Color {
    const val BLACK: Int = -0x1000000
    const val WHITE: Int = -0x1
    const val TRANSPARENT: Int = 0x00000000
    fun argb(a:Int,r:Int,g:Int,b:Int):Int = ((a and 255) shl 24) or ((r and 255) shl 16) or ((g and 255) shl 8) or (b and 255)
    fun rgb(r:Int,g:Int,b:Int):Int = argb(255,r,g,b)
    fun alpha(c:Int)= (c ushr 24) and 255
    fun red(c:Int)= (c ushr 16) and 255
    fun green(c:Int)= (c ushr 8) and 255
    fun blue(c:Int)= c and 255
}

open class Shader { enum class TileMode { CLAMP } }
class LinearGradient(val x0:Float,val y0:Float,val x1:Float,val y1:Float,val colors:IntArray,val positions:FloatArray?,val mode:Shader.TileMode):Shader()

class RectF(var left:Float=0f,var top:Float=0f,var right:Float=0f,var bottom:Float=0f) {
    fun width() = right-left
    fun height() = bottom-top
}

class Matrix {
    internal var transform = AffineTransform()
    fun setValues(v:FloatArray) { transform = AffineTransform(v[0].toDouble(),v[3].toDouble(),v[1].toDouble(),v[4].toDouble(),v[2].toDouble(),v[5].toDouble()) }
}

class Path {
    enum class FillType { WINDING }
    internal val p = Path2D.Float(Path2D.WIND_NON_ZERO)
    var fillType: FillType = FillType.WINDING
    fun moveTo(x:Float,y:Float){ p.moveTo(x.toDouble(),y.toDouble()) }
    fun lineTo(x:Float,y:Float){ p.lineTo(x.toDouble(),y.toDouble()) }
    fun quadTo(cx:Float,cy:Float,x:Float,y:Float){ p.quadTo(cx.toDouble(),cy.toDouble(),x.toDouble(),y.toDouble()) }
    fun close(){ p.closePath() }
    fun computeBounds(out:RectF, exact:Boolean){ val b=p.bounds2D; out.left=b.minX.toFloat(); out.top=b.minY.toFloat(); out.right=b.maxX.toFloat(); out.bottom=b.maxY.toFloat() }
}

class Typeface { companion object { val DEFAULT = Typeface() } }

class Paint(val flags:Int=0) {
    companion object { const val ANTI_ALIAS_FLAG=1 }
    enum class Style { FILL, STROKE }
    enum class Cap { ROUND }
    enum class Join { ROUND }
    var alpha:Int=255
    var style:Style=Style.FILL
    var strokeWidth:Float=1f
    var strokeCap:Cap=Cap.ROUND
    var strokeJoin:Join=Join.ROUND
    var color:Int=Color.BLACK
    var shader:Shader?=null
    var textSize:Float=12f
    var typeface: Typeface = Typeface.DEFAULT
    data class FontMetrics(val ascent:Float)
    data class FontMetricsInt(val ascent:Int, val descent:Int)
    val fontMetrics:FontMetrics get() = FontMetrics(-textSize*0.8f)
    val fontMetricsInt:FontMetricsInt get() = FontMetricsInt((-textSize*0.8f).toInt(), (textSize*0.2f).toInt().coerceAtLeast(1))
    fun measureText(text:String):Float = text.length * textSize * 0.6f
}

class Bitmap internal constructor(internal val image:BufferedImage) {
    enum class Config { ARGB_8888, RGB_565 }
    val width get()=image.width
    val height get()=image.height
    fun copy(config:Config, mutable:Boolean):Bitmap { val out=BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB); out.graphics.drawImage(image,0,0,null); return Bitmap(out) }
    fun recycle() {}
    fun copyPixelsFromBuffer(buffer: java.nio.Buffer) {}
    fun getPixels(dst:IntArray,offset:Int,stride:Int,x:Int,y:Int,w:Int,h:Int){ var k=offset; for(yy in 0 until h){ for(xx in 0 until w){ dst[k+xx]=image.getRGB(x+xx,y+yy) }; k+=stride } }
    companion object {
        fun createBitmap(w:Int,h:Int,config:Config):Bitmap = Bitmap(BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB))
        fun createBitmap(pixels:IntArray,w:Int,h:Int,config:Config):Bitmap { val b=BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB); b.setRGB(0,0,w,h,pixels,0,w); return Bitmap(b) }
    }
}
object BitmapFactory {
    fun decodeByteArray(data:ByteArray,offset:Int,length:Int):Bitmap? = ImageIO.read(ByteArrayInputStream(data,offset,length))?.let(::Bitmap)
}

class Canvas(private val bitmap:Bitmap) {
    private val g:Graphics2D = bitmap.image.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    }
    private data class State(val tx:AffineTransform,val clip:java.awt.Shape?)
    private val states= mutableListOf<State>()
    fun drawColor(color:Int){ g.color=java.awt.Color(color,true); g.fillRect(0,0,bitmap.width,bitmap.height) }
    fun save():Int { states += State(AffineTransform(g.transform), g.clip); return states.size }
    fun restoreToCount(count:Int){ if(states.isEmpty()) return; val s=states.removeAt(states.lastIndex); g.transform=s.tx; g.clip=s.clip }
    fun concat(m:Matrix){ g.transform(m.transform) }
    fun clipPath(path:Path){ g.clip(path.p) }
    private fun applyPaint(p:Paint){
        g.composite=AlphaComposite.getInstance(AlphaComposite.SRC_OVER,(p.alpha.coerceIn(0,255)/255f))
        g.color=java.awt.Color(p.color,true)
        if(p.style==Paint.Style.STROKE) g.stroke=BasicStroke(p.strokeWidth,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND)
        val sh=p.shader
        if(sh is LinearGradient){ val a=java.awt.Color(sh.colors.first(),true); val z=java.awt.Color(sh.colors.last(),true); g.paint=GradientPaint(sh.x0,sh.y0,a,sh.x1,sh.y1,z) }
    }
    fun drawBitmap(b:Bitmap,x:Float,y:Float,p:Paint){ applyPaint(p); g.drawImage(b.image,x.roundToInt(),y.roundToInt(),null) }
    fun drawBitmap(b:Bitmap,src:Any?,dst:RectF,p:Paint){ applyPaint(p); g.drawImage(b.image,dst.left.roundToInt(),dst.top.roundToInt(),dst.right.roundToInt(),dst.bottom.roundToInt(),0,0,b.width,b.height,null) }
    fun drawPath(path:Path,p:Paint){ applyPaint(p); if(p.style==Paint.Style.FILL) g.fill(path.p) else g.draw(path.p) }
    fun drawText(s:String,x:Float,y:Float,p:Paint){ applyPaint(p); g.font=Font(Font.SANS_SERIF,Font.PLAIN,p.textSize.roundToInt().coerceAtLeast(1)); g.drawString(s,x,y) }
}
