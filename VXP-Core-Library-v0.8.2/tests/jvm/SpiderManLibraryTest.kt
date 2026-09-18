import vxpcore.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: SpiderManLibraryTest <file.vxp>" }
    val file = File(args[0])
    val done = CountDownLatch(1)
    var lastFrame: FrameSnapshot? = null
    var result: VxpRunResult? = null
    val session = VxpCoreLibrary.open(
        bytes = file.readBytes(),
        fileName = file.name,
        storageRoot = File("/mnt/data/VXP-Core-Library-v0.8/build/test-fs"),
        options = VxpSessionOptions(maxRuntimeMs = 6000L),
        listener = object : VxpCoreListener {
            override fun onFrame(frame: FrameSnapshot) { lastFrame = frame }
            override fun onResult(r: VxpRunResult) { result = r; done.countDown() }
            override fun onState(state: VxpSessionState) {
                if (state is VxpSessionState.Failed) {
                    System.err.println("FAILED: ${state.message}")
                    state.cause?.printStackTrace()
                    done.countDown()
                }
            }
        }
    )
    println("backend=${session.backend}")
    session.start()
    done.await(15, TimeUnit.SECONDS)
    session.close()
    val r = result ?: error("No result")
    val frame = lastFrame ?: error("No frame")
    val md = MessageDigest.getInstance("SHA-256")
    val bytes = ByteArray(frame.pixels.size * 2)
    for (i in frame.pixels.indices) {
        val v = frame.pixels[i].toInt() and 0xffff
        bytes[i*2] = (v and 0xff).toByte(); bytes[i*2+1] = (v ushr 8).toByte()
    }
    val sha = md.digest(bytes).joinToString("") { "%02x".format(it) }
    println("instructions=${r.instructions}")
    println("frames=${r.frames}")
    println("events=${r.events}")
    println("stubbed=${r.stubbedSymbols.joinToString()}")
    println("frame=${frame.width}x${frame.height} serial=${frame.serial} sha256=$sha")
}
