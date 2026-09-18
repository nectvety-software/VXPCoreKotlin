import java.io.File
import java.security.MessageDigest
import vxpcore.FrameSnapshot
import vxpcore.VxpKey
import vxpcore.android.FlashLiteAndroidBackend

fun hash(frame: FrameSnapshot): String {
    val raw=ByteArray(frame.pixels.size*2)
    frame.pixels.forEachIndexed { i,s -> val v=s.toInt() and 0xffff; raw[i*2]=(v and 255).toByte(); raw[i*2+1]=(v ushr 8).toByte() }
    return MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
}
fun main(args:Array<String>) {
    val bytes=File(args[0]).readBytes()
    val menu=FlashLiteAndroidBackend.render(bytes, preferMenu=true)
    println("menu frame=${menu.renderedFrame} ${menu.width}x${menu.height} shapes=${menu.shapes} buttons=${menu.buttons} sha256=${hash(menu.frame)}")
    val start=FlashLiteAndroidBackend.render(bytes, preferMenu=true, inputKeys=listOf(VxpKey.OK))
    println("after OK frame=${start.renderedFrame} actions=${start.avm1ActionsExecuted} inputs=${start.inputEvents} playing=${start.playing} sha256=${hash(start.frame)}")
    require(menu.renderedFrame==4) { "expected menu frame 4" }
    require(start.renderedFrame==5) { "expected gameplay frame 5" }
    require(start.playing)
}
