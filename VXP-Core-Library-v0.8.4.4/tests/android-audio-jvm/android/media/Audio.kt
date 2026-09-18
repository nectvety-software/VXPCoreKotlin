package android.media

import android.os.Handler

class AudioAttributes private constructor() {
    class Builder {
        fun setUsage(v: Int) = this
        fun setContentType(v: Int) = this
        fun build() = AudioAttributes()
    }
    companion object {
        const val USAGE_GAME = 14
        const val CONTENT_TYPE_MUSIC = 2
        const val CONTENT_TYPE_SONIFICATION = 4
    }
}

class AudioFocusRequest internal constructor(val listener: AudioManager.OnAudioFocusChangeListener?) {
    class Builder(private val gain: Int) {
        private var listener: AudioManager.OnAudioFocusChangeListener? = null
        fun setAudioAttributes(a: AudioAttributes) = this
        fun setAcceptsDelayedFocusGain(v: Boolean) = this
        fun setWillPauseWhenDucked(v: Boolean) = this
        fun setOnAudioFocusChangeListener(l: AudioManager.OnAudioFocusChangeListener, h: Handler): Builder {
            listener = l; return this
        }
        fun build() = AudioFocusRequest(listener)
    }
}

open class AudioManager {
    fun interface OnAudioFocusChangeListener { fun onAudioFocusChange(change: Int) }
    open fun requestAudioFocus(request: AudioFocusRequest): Int = AUDIOFOCUS_REQUEST_GRANTED
    open fun requestAudioFocus(listener: OnAudioFocusChangeListener, streamType: Int, durationHint: Int): Int = AUDIOFOCUS_REQUEST_GRANTED
    open fun abandonAudioFocusRequest(request: AudioFocusRequest): Int = AUDIOFOCUS_REQUEST_GRANTED
    open fun abandonAudioFocus(listener: OnAudioFocusChangeListener): Int = AUDIOFOCUS_REQUEST_GRANTED
    companion object {
        const val STREAM_MUSIC = 3
        const val AUDIOFOCUS_GAIN = 1
        const val AUDIOFOCUS_LOSS = -1
        const val AUDIOFOCUS_LOSS_TRANSIENT = -2
        const val AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK = -3
        const val AUDIOFOCUS_REQUEST_GRANTED = 1
    }
}

class AudioFormat private constructor() {
    class Builder {
        fun setEncoding(v: Int) = this
        fun setSampleRate(v: Int) = this
        fun setChannelMask(v: Int) = this
        fun build() = AudioFormat()
    }
    companion object {
        const val ENCODING_PCM_8BIT = 3
        const val ENCODING_PCM_16BIT = 2
        const val CHANNEL_OUT_MONO = 4
        const val CHANNEL_OUT_STEREO = 12
    }
}

open class AudioTrack {
    open var playState: Int = PLAYSTATE_STOPPED
    open var playbackHeadPosition: Int = 0
    open var notificationMarkerPosition: Int = 0
    var loopEnabled: Boolean = false
    var volume: Float = 1f
    class Builder {
        fun setAudioAttributes(a: AudioAttributes) = this
        fun setAudioFormat(f: AudioFormat) = this
        fun setBufferSizeInBytes(v: Int) = this
        fun setTransferMode(v: Int) = this
        fun build() = AudioTrack()
    }
    interface OnPlaybackPositionUpdateListener {
        fun onMarkerReached(audioTrack: AudioTrack?)
        fun onPeriodicNotification(audioTrack: AudioTrack?)
    }
    open fun play() { playState = PLAYSTATE_PLAYING }
    open fun pause() { playState = PLAYSTATE_PAUSED }
    open fun stop() { playState = PLAYSTATE_STOPPED }
    open fun flush() = Unit
    open fun release() = Unit
    open fun setVolume(v: Float): Int { volume = v; return SUCCESS }
    open fun write(data: ByteArray, offset: Int, size: Int): Int = size
    open fun setLoopPoints(start: Int, end: Int, count: Int): Int { loopEnabled = count != 0; return SUCCESS }
    open fun setPlaybackHeadPosition(position: Int): Int { playbackHeadPosition = position; return SUCCESS }
    open fun setPlaybackPositionUpdateListener(listener: OnPlaybackPositionUpdateListener, handler: Handler) = Unit
    companion object {
        const val MODE_STATIC = 0
        const val SUCCESS = 0
        const val PLAYSTATE_STOPPED = 1
        const val PLAYSTATE_PAUSED = 2
        const val PLAYSTATE_PLAYING = 3
        fun getMinBufferSize(rate: Int, mask: Int, enc: Int): Int = 256
    }
}

open class MediaPlayer {
    open var isLooping: Boolean = false
    private var playing = false
    private var pos = 0
    open val isPlaying: Boolean get() = playing
    open val currentPosition: Int get() = pos
    open val duration: Int get() = 1000
    fun interface OnCompletionListener { fun onCompletion(mp: MediaPlayer?) }
    fun interface OnErrorListener { fun onError(mp: MediaPlayer?, what: Int, extra: Int): Boolean }
    open fun setAudioAttributes(a: AudioAttributes) = Unit
    open fun setDataSource(path: String) = Unit
    open fun setVolume(l: Float, r: Float) = Unit
    open fun setOnCompletionListener(l: OnCompletionListener?) = Unit
    open fun setOnErrorListener(l: OnErrorListener?) = Unit
    open fun prepare() = Unit
    open fun start() { playing = true }
    open fun pause() { playing = false }
    open fun stop() { playing = false }
    open fun reset() = Unit
    open fun release() = Unit
    open fun seekTo(ms: Int) { pos = ms }
    open fun seekTo(ms: Long, mode: Int) { pos = ms.toInt() }
    companion object { const val SEEK_CLOSEST = 3 }
}
