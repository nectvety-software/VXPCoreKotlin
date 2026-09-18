import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import java.io.File
import vxpcore.*
import vxpcore.android.AndroidMreAudioHost

private class FakeManager : AudioManager() {
    var request26 = 0
    var requestLegacy = 0
    var abandon26 = 0
    var abandonLegacy = 0
    var listener: OnAudioFocusChangeListener? = null

    override fun requestAudioFocus(request: AudioFocusRequest): Int {
        request26++
        listener = request.listener
        return AUDIOFOCUS_REQUEST_GRANTED
    }
    override fun requestAudioFocus(listener: OnAudioFocusChangeListener, streamType: Int, durationHint: Int): Int {
        requestLegacy++
        this.listener = listener
        return AUDIOFOCUS_REQUEST_GRANTED
    }
    override fun abandonAudioFocusRequest(request: AudioFocusRequest): Int { abandon26++; return AUDIOFOCUS_REQUEST_GRANTED }
    override fun abandonAudioFocus(listener: OnAudioFocusChangeListener): Int { abandonLegacy++; return AUDIOFOCUS_REQUEST_GRANTED }
    fun focus(change: Int) = listener?.onAudioFocusChange(change)
}

private fun wav8Mono(): ByteArray {
    val rate = 8000
    val data = ByteArray(rate) { 0x80.toByte() }
    val out = ByteArray(44 + data.size)
    fun ascii(off: Int, v: String) = v.forEachIndexed { i, c -> out[off+i] = c.code.toByte() }
    fun le16(off: Int, v: Int) { out[off]=v.toByte(); out[off+1]=(v ushr 8).toByte() }
    fun le32(off: Int, v: Int) { out[off]=v.toByte(); out[off+1]=(v ushr 8).toByte(); out[off+2]=(v ushr 16).toByte(); out[off+3]=(v ushr 24).toByte() }
    ascii(0,"RIFF"); le32(4,out.size-8); ascii(8,"WAVE"); ascii(12,"fmt "); le32(16,16)
    le16(20,1); le16(22,1); le32(24,rate); le32(28,rate); le16(32,1); le16(34,8)
    ascii(36,"data"); le32(40,data.size); data.copyInto(out,44)
    return out
}

private fun runFocusCase(sdk: Int) {
    Build.VERSION.SDK_INT = sdk
    val manager = FakeManager()
    val cache = File("/mnt/data/VXP-Core-Library-v0.8.4.4/build/android_audio_host_$sdk").apply { deleteRecursively(); mkdirs() }
    val host = AndroidMreAudioHost(cache, manager)
    val events = mutableListOf<MreAudioHostEvent>()
    require(host.play(MreAudioRequest(7, MreAudioKind.AUDIO, MreAudioSource.Bytes(wav8Mono())), events::add))
    if (sdk >= 26) require(manager.request26 == 1 && manager.requestLegacy == 0)
    else require(manager.requestLegacy == 1 && manager.request26 == 0)
    require(host.snapshot().state == MreAudioPlaybackState.PLAYING)
    require(host.snapshot().durationMs == 1000)

    require(host.seekTo(250, MreAudioKind.AUDIO))
    require(host.snapshot().positionMs == 250)
    require(host.setLooping(true, MreAudioKind.AUDIO))
    require(host.snapshot().loop)

    manager.focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
    require(host.snapshot().state == MreAudioPlaybackState.PAUSED)
    require(events.last().type == MreAudioHostEventType.INTERRUPTED)
    manager.focus(AudioManager.AUDIOFOCUS_GAIN)
    require(host.snapshot().state == MreAudioPlaybackState.PLAYING)
    require(events.last().type == MreAudioHostEventType.RESUMED)

    host.onHostPause()
    require(host.snapshot().state == MreAudioPlaybackState.PAUSED)
    host.onHostResume()
    require(host.snapshot().state == MreAudioPlaybackState.PLAYING)

    require(host.stop())
    if (sdk >= 26) require(manager.abandon26 >= 1) else require(manager.abandonLegacy >= 1)
    host.close()
}

fun main() {
    runFocusCase(35)
    runFocusCase(25)
    println("[OK] v0.8.4.4 Android audio focus/lifecycle regression passed")
}
