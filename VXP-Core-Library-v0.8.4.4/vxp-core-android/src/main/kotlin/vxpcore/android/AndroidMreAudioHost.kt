package vxpcore.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import vxpcore.*

/**
 * Android audio backend for the JVM-neutral MRE audio bridge.
 *
 * v0.8.4.4 accuracy/system-integration changes:
 * - MediaPlayer reports native duration/currentPosition and supports seek/loop state.
 * - AudioTrack MODE_STATIC keeps a logical base frame so position remains correct
 *   after seek and across the unsigned playback-head counter.
 * - playback is armed before start, avoiding lost completion callbacks for very
 *   short sounds.
 * - optional Android AudioManager integration requests/abandons audio focus and
 *   handles transient loss/duck/gain without exposing Android types to vxp-core.
 */
class AndroidMreAudioHost(
    private val cacheDir: File,
    private val audioManager: AudioManager? = null
) : MreAudioHost {

    constructor(context: Context, cacheDir: File) : this(
        cacheDir,
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    )

    private sealed interface Active {
        val id: Int
        val kind: MreAudioKind
        val sink: (MreAudioHostEvent) -> Unit
        val tempFile: File?
        fun start()
        fun pause()
        fun resume()
        fun seekTo(positionMs: Int): Boolean
        fun setLooping(loop: Boolean): Boolean
        fun stopAndRelease()
        fun setVolume(volume: Float)
        fun isPlaying(): Boolean
        fun positionMs(): Int
        fun durationMs(): Int
        fun isLooping(): Boolean
    }

    private class MediaActive(
        override val id: Int,
        override val kind: MreAudioKind,
        override val sink: (MreAudioHostEvent) -> Unit,
        override val tempFile: File?,
        val player: MediaPlayer
    ) : Active {
        override fun start() { player.start() }
        override fun pause() { if (player.isPlaying) player.pause() }
        override fun resume() { if (!player.isPlaying) player.start() }
        override fun seekTo(positionMs: Int): Boolean = runCatching {
            val target = positionMs.coerceIn(0, durationMs().coerceAtLeast(0))
            if (Build.VERSION.SDK_INT >= 26) player.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
            else @Suppress("DEPRECATION") player.seekTo(target)
            true
        }.getOrDefault(false)
        override fun setLooping(loop: Boolean): Boolean = runCatching {
            player.isLooping = loop
            true
        }.getOrDefault(false)
        override fun stopAndRelease() {
            runCatching { player.stop() }
            runCatching { player.reset() }
            runCatching { player.release() }
            tempFile?.let { runCatching { it.delete() } }
        }
        override fun setVolume(volume: Float) { player.setVolume(volume, volume) }
        override fun isPlaying(): Boolean = runCatching { player.isPlaying }.getOrDefault(false)
        override fun positionMs(): Int = runCatching { player.currentPosition.coerceAtLeast(0) }.getOrDefault(0)
        override fun durationMs(): Int = runCatching { player.duration.coerceAtLeast(0) }.getOrDefault(0)
        override fun isLooping(): Boolean = runCatching { player.isLooping }.getOrDefault(false)
    }

    private class TrackActive(
        override val id: Int,
        override val kind: MreAudioKind,
        override val sink: (MreAudioHostEvent) -> Unit,
        val track: AudioTrack,
        private val sampleRate: Int,
        private val totalFrames: Int,
        initialFrame: Int,
        private var loop: Boolean
    ) : Active {
        override val tempFile: File? = null
        private var baseFrame: Long = initialFrame.toLong()
        private var headCounterBase: Long = unsignedHead()

        override fun start() { track.play() }
        override fun pause() { if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause() }
        override fun resume() { if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play() }

        override fun seekTo(positionMs: Int): Boolean {
            if (positionMs < 0 || totalFrames <= 0) return false
            val targetFrame = ((positionMs.toLong() * sampleRate) / 1000L)
                .coerceIn(0L, totalFrames.toLong())
                .toInt()
            val wasPlaying = track.playState == AudioTrack.PLAYSTATE_PLAYING
            return runCatching {
                if (wasPlaying) track.pause()
                val result = track.setPlaybackHeadPosition(targetFrame)
                if (result != AudioTrack.SUCCESS) {
                    if (wasPlaying) track.play()
                    return@runCatching false
                }
                baseFrame = targetFrame.toLong()
                headCounterBase = unsignedHead()
                updateCompletionMarker()
                if (wasPlaying) track.play()
                true
            }.getOrDefault(false)
        }

        override fun setLooping(loop: Boolean): Boolean {
            if (totalFrames <= 0) return false
            val wasPlaying = track.playState == AudioTrack.PLAYSTATE_PLAYING
            return runCatching {
                val current = currentLogicalFrame().coerceIn(0L, totalFrames.toLong()).toInt()
                if (wasPlaying) track.pause()
                val result = if (loop) track.setLoopPoints(0, totalFrames, -1) else track.setLoopPoints(0, 0, 0)
                if (result != AudioTrack.SUCCESS) {
                    if (wasPlaying) track.play()
                    return@runCatching false
                }
                this.loop = loop
                // Re-anchor logical time because the playback-head counter for MODE_STATIC
                // is a cumulative frame counter, not the current buffer offset.
                baseFrame = current.toLong()
                headCounterBase = unsignedHead()
                updateCompletionMarker()
                if (wasPlaying) track.play()
                true
            }.getOrDefault(false)
        }

        override fun stopAndRelease() {
            runCatching { track.stop() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }

        override fun setVolume(volume: Float) { track.setVolume(volume) }
        override fun isPlaying(): Boolean = track.playState == AudioTrack.PLAYSTATE_PLAYING

        override fun positionMs(): Int {
            val frame = currentLogicalFrame()
            return ((frame * 1000L) / sampleRate.coerceAtLeast(1))
                .coerceIn(0L, Int.MAX_VALUE.toLong())
                .toInt()
        }

        override fun durationMs(): Int = ((totalFrames.toLong() * 1000L) / sampleRate.coerceAtLeast(1))
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

        override fun isLooping(): Boolean = loop

        fun updateCompletionMarker() {
            if (loop || totalFrames <= 0) {
                runCatching { track.notificationMarkerPosition = 0 }
                return
            }
            val current = currentLogicalFrame().coerceIn(0L, totalFrames.toLong())
            val remaining = (totalFrames.toLong() - current).coerceAtLeast(0L)
            val markerUnsigned = (unsignedHead() + remaining) and 0xffffffffL
            // Marker value 0 disables notifications; one-frame displacement is a
            // better approximation than silently losing completion at uint32 wrap.
            val marker = if (markerUnsigned == 0L) 1 else markerUnsigned.toInt()
            runCatching { track.notificationMarkerPosition = marker }
        }

        private fun currentLogicalFrame(): Long {
            val delta = (unsignedHead() - headCounterBase) and 0xffffffffL
            val raw = baseFrame + delta
            return if (loop && totalFrames > 0) raw % totalFrames.toLong() else raw.coerceAtMost(totalFrames.toLong())
        }

        private fun unsignedHead(): Long = track.playbackHeadPosition.toLong() and 0xffffffffL
    }

    private data class WavPcm(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val pcm: ByteArray
    )

    private val lock = Any()
    private val tempSeq = AtomicInteger(1)
    private val callbackThread = HandlerThread("VXP-Audio-Callbacks").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private var active: Active? = null
    private var state = MreAudioPlaybackState.STOPPED
    private var volume = 1f
    private var closed = false

    private var focusHeld = false
    private var focusRequest26: Any? = null
    private var resumeOnFocusGain = false
    private var lifecycleResumePending = false
    private var ducked = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change -> handleFocusChange(change) }

    init { cacheDir.mkdirs() }

    override fun play(request: MreAudioRequest, eventSink: (MreAudioHostEvent) -> Unit): Boolean {
        synchronized(lock) {
            if (closed) return false
            stopLocked(abandonFocus = true)
            volume = request.volume.coerceIn(0f, 1f)
            return try {
                val next = when (val source = request.source) {
                    is MreAudioSource.Bytes -> {
                        val wav = parsePcmWav(source.bytes)
                        if (request.kind == MreAudioKind.AUDIO && wav != null) {
                            createAudioTrack(request, wav, eventSink)
                        } else {
                            val suffix = guessSuffix(source.bytes, request.kind)
                            val temp = File(cacheDir, "vxp_audio_${tempSeq.getAndIncrement()}$suffix")
                            temp.writeBytes(source.bytes)
                            createMediaPlayer(request, temp, temp, eventSink)
                        }
                    }
                    is MreAudioSource.SandboxFile -> createMediaPlayer(request, source.file, null, eventSink)
                }

                if (!requestFocusLocked()) {
                    next.stopAndRelease()
                    return false
                }

                // Arm active state before start: very short AudioTrack buffers can reach
                // their marker almost immediately after play().
                active = next
                state = MreAudioPlaybackState.PLAYING
                resumeOnFocusGain = false
                lifecycleResumePending = false
                next.start()
                true
            } catch (_: Throwable) {
                stopLocked(abandonFocus = true)
                false
            }
        }
    }

    override fun pause(kind: MreAudioKind?): Boolean = synchronized(lock) {
        val a = active ?: return@synchronized false
        if (kind != null && a.kind != kind) return@synchronized false
        if (state != MreAudioPlaybackState.PLAYING) return@synchronized false
        return@synchronized runCatching {
            a.pause()
            state = MreAudioPlaybackState.PAUSED
            resumeOnFocusGain = false
            true
        }.getOrDefault(false)
    }

    override fun resume(kind: MreAudioKind?): Boolean = synchronized(lock) {
        val a = active ?: return@synchronized false
        if (kind != null && a.kind != kind) return@synchronized false
        if (state != MreAudioPlaybackState.PAUSED) return@synchronized false
        if (!requestFocusLocked()) return@synchronized false
        return@synchronized runCatching {
            a.resume()
            state = MreAudioPlaybackState.PLAYING
            resumeOnFocusGain = false
            lifecycleResumePending = false
            true
        }.getOrDefault(false)
    }

    override fun stop(kind: MreAudioKind?): Boolean = synchronized(lock) {
        val a = active
        if (a == null) {
            abandonFocusLocked()
            return@synchronized true
        }
        if (kind != null && a.kind != kind) return@synchronized false
        stopLocked(abandonFocus = true)
        true
    }

    override fun seekTo(positionMs: Int, kind: MreAudioKind?): Boolean = synchronized(lock) {
        val a = active ?: return@synchronized false
        if (positionMs < 0 || (kind != null && a.kind != kind)) return@synchronized false
        a.seekTo(positionMs)
    }

    override fun setLooping(loop: Boolean, kind: MreAudioKind?): Boolean = synchronized(lock) {
        val a = active ?: return@synchronized false
        if (kind != null && a.kind != kind) return@synchronized false
        a.setLooping(loop)
    }

    override fun setVolume(volume: Float): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        this.volume = volume.coerceIn(0f, 1f)
        return@synchronized runCatching {
            active?.setVolume(effectiveVolumeLocked())
            true
        }.getOrDefault(false)
    }

    override fun snapshot(): MreAudioSnapshot = synchronized(lock) {
        val a = active
        if (a == null) MreAudioSnapshot(volume = volume)
        else MreAudioSnapshot(
            state = state,
            playbackId = a.id,
            kind = a.kind,
            positionMs = runCatching { a.positionMs() }.getOrDefault(0),
            durationMs = runCatching { a.durationMs() }.getOrDefault(0),
            volume = volume,
            loop = runCatching { a.isLooping() }.getOrDefault(false)
        )
    }

    override fun onHostPause() {
        var dispatch: Pair<(MreAudioHostEvent) -> Unit, MreAudioHostEvent>? = null
        synchronized(lock) {
            val a = active ?: return
            if (state != MreAudioPlaybackState.PLAYING) return
            runCatching { a.pause() }.onSuccess {
                state = MreAudioPlaybackState.PAUSED
                lifecycleResumePending = true
                resumeOnFocusGain = false
                abandonFocusLocked()
                dispatch = a.sink to MreAudioHostEvent(a.id, a.kind, MreAudioHostEventType.INTERRUPTED, HOST_INTERRUPT_LIFECYCLE)
            }
        }
        dispatch?.let { it.first(it.second) }
    }

    override fun onHostResume() {
        var dispatch: Pair<(MreAudioHostEvent) -> Unit, MreAudioHostEvent>? = null
        synchronized(lock) {
            val a = active ?: return
            if (!lifecycleResumePending || state != MreAudioPlaybackState.PAUSED) return
            if (!requestFocusLocked()) return
            runCatching { a.resume() }.onSuccess {
                state = MreAudioPlaybackState.PLAYING
                lifecycleResumePending = false
                dispatch = a.sink to MreAudioHostEvent(a.id, a.kind, MreAudioHostEventType.RESUMED, HOST_INTERRUPT_LIFECYCLE)
            }
        }
        dispatch?.let { it.first(it.second) }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            stopLocked(abandonFocus = true)
        }
        callbackThread.quitSafely()
    }

    private fun createMediaPlayer(
        request: MreAudioRequest,
        sourceFile: File,
        tempFile: File?,
        sink: (MreAudioHostEvent) -> Unit
    ): Active {
        val player = MediaPlayer()
        val wrapper = MediaActive(request.playbackId, request.kind, sink, tempFile, player)
        player.setAudioAttributes(audioAttributes(request.kind))
        player.setDataSource(sourceFile.absolutePath)
        player.isLooping = request.loop
        player.setVolume(effectiveVolumeLocked(), effectiveVolumeLocked())
        player.setOnCompletionListener {
            if (!request.loop) finish(request.playbackId, MreAudioHostEventType.COMPLETED, 0)
        }
        player.setOnErrorListener { _, what, extra ->
            finish(request.playbackId, MreAudioHostEventType.ERROR, if (what != 0) what else extra)
            true
        }
        player.prepare()
        if (request.startPositionMs > 0) wrapper.seekTo(request.startPositionMs)
        return wrapper
    }

    private fun createAudioTrack(
        request: MreAudioRequest,
        wav: WavPcm,
        sink: (MreAudioHostEvent) -> Unit
    ): Active {
        val encoding = if (wav.bitsPerSample == 8) AudioFormat.ENCODING_PCM_8BIT else AudioFormat.ENCODING_PCM_16BIT
        val channelMask = if (wav.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val bytesPerFrame = wav.channels * (wav.bitsPerSample / 8)
        val frames = wav.pcm.size / bytesPerFrame.coerceAtLeast(1)
        require(frames > 0)
        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(wav.sampleRate)
            .setChannelMask(channelMask)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(wav.sampleRate, channelMask, encoding).coerceAtLeast(0)
        val track = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes(request.kind))
            .setAudioFormat(format)
            .setBufferSizeInBytes(wav.pcm.size.coerceAtLeast(minBuffer))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        val written = track.write(wav.pcm, 0, wav.pcm.size)
        require(written == wav.pcm.size)
        track.setVolume(effectiveVolumeLocked())

        val startFrame = ((request.startPositionMs.coerceAtLeast(0).toLong() * wav.sampleRate) / 1000L)
            .coerceIn(0L, frames.toLong()).toInt()
        if (request.loop) require(track.setLoopPoints(0, frames, -1) == AudioTrack.SUCCESS)
        if (startFrame > 0) require(track.setPlaybackHeadPosition(startFrame) == AudioTrack.SUCCESS)

        val wrapper = TrackActive(request.playbackId, request.kind, sink, track, wav.sampleRate, frames, startFrame, request.loop)
        if (!request.loop) {
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(audioTrack: AudioTrack?) {
                    finish(request.playbackId, MreAudioHostEventType.COMPLETED, 0)
                }
                override fun onPeriodicNotification(audioTrack: AudioTrack?) = Unit
            }, callbackHandler)
            wrapper.updateCompletionMarker()
        }
        return wrapper
    }

    private fun finish(id: Int, type: MreAudioHostEventType, code: Int) {
        val event: MreAudioHostEvent
        val sink: (MreAudioHostEvent) -> Unit
        synchronized(lock) {
            val a = active ?: return
            if (a.id != id) return
            sink = a.sink
            runCatching { a.stopAndRelease() }
            active = null
            state = MreAudioPlaybackState.STOPPED
            resumeOnFocusGain = false
            lifecycleResumePending = false
            ducked = false
            abandonFocusLocked()
            event = MreAudioHostEvent(id, a.kind, type, code)
        }
        // Never call into the core while holding the Android audio lock.
        sink(event)
    }

    private fun stopLocked(abandonFocus: Boolean) {
        val a = active
        active = null
        state = MreAudioPlaybackState.STOPPED
        resumeOnFocusGain = false
        lifecycleResumePending = false
        ducked = false
        if (a != null) runCatching { a.stopAndRelease() }
        if (abandonFocus) abandonFocusLocked()
    }

    private fun requestFocusLocked(): Boolean {
        val manager = audioManager ?: return true
        if (focusHeld) return true
        val result = if (Build.VERSION.SDK_INT >= 26) {
            val request = (focusRequest26 as? AudioFocusRequest) ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes(MreAudioKind.AUDIO))
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(focusListener, callbackHandler)
                .build()
                .also { focusRequest26 = it }
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focusHeld
    }

    private fun abandonFocusLocked() {
        val manager = audioManager ?: return
        if (!focusHeld && focusRequest26 == null) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                (focusRequest26 as? AudioFocusRequest)?.let { manager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION") manager.abandonAudioFocus(focusListener)
            }
        }
        focusHeld = false
    }

    private fun handleFocusChange(change: Int) {
        var dispatch: Pair<(MreAudioHostEvent) -> Unit, MreAudioHostEvent>? = null
        synchronized(lock) {
            val a = active ?: return
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    focusHeld = true
                    if (ducked) {
                        ducked = false
                        runCatching { a.setVolume(volume) }
                    }
                    if (resumeOnFocusGain && !lifecycleResumePending && state == MreAudioPlaybackState.PAUSED) {
                        runCatching { a.resume() }.onSuccess {
                            state = MreAudioPlaybackState.PLAYING
                            resumeOnFocusGain = false
                            dispatch = a.sink to MreAudioHostEvent(a.id, a.kind, MreAudioHostEventType.RESUMED, HOST_INTERRUPT_FOCUS)
                        }
                    }
                    Unit
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    if (state == MreAudioPlaybackState.PLAYING) {
                        runCatching { a.pause() }.onSuccess {
                            state = MreAudioPlaybackState.PAUSED
                            resumeOnFocusGain = true
                            dispatch = a.sink to MreAudioHostEvent(a.id, a.kind, MreAudioHostEventType.INTERRUPTED, HOST_INTERRUPT_FOCUS)
                        }
                    }
                    Unit
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    ducked = true
                    runCatching { a.setVolume((volume * DUCK_FACTOR).coerceIn(0f, 1f)) }
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    focusHeld = false
                    resumeOnFocusGain = false
                    if (state == MreAudioPlaybackState.PLAYING) {
                        runCatching { a.pause() }.onSuccess {
                            state = MreAudioPlaybackState.PAUSED
                            dispatch = a.sink to MreAudioHostEvent(a.id, a.kind, MreAudioHostEventType.INTERRUPTED, HOST_INTERRUPT_FOCUS_PERMANENT)
                        }
                    }
                    Unit
                }
                else -> Unit
            }
        }
        dispatch?.let { it.first(it.second) }
    }

    private fun effectiveVolumeLocked(): Float = if (ducked) (volume * DUCK_FACTOR).coerceIn(0f, 1f) else volume

    private fun audioAttributes(kind: MreAudioKind): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(if (kind == MreAudioKind.MIDI) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun guessSuffix(bytes: ByteArray, kind: MreAudioKind): String {
        if (kind == MreAudioKind.MIDI || (bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf('M'.code.toByte(), 'T'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte())))) return ".mid"
        if (bytes.size >= 3 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) return ".mp3"
        if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") return ".wav"
        if (bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) == "#!AMR\n") return ".amr"
        return ".bin"
    }

    private fun parsePcmWav(bytes: ByteArray): WavPcm? {
        if (bytes.size < 44) return null
        if (ascii(bytes, 0, 4) != "RIFF" || ascii(bytes, 8, 4) != "WAVE") return null
        var pos = 12
        var format = -1
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var dataOffset = -1
        var dataSize = 0
        while (pos + 8 <= bytes.size) {
            val id = ascii(bytes, pos, 4)
            val size = le32(bytes, pos + 4)
            if (size < 0 || pos + 8L + size.toLong() > bytes.size.toLong()) return null
            val body = pos + 8
            when (id) {
                "fmt " -> if (size >= 16) {
                    format = le16(bytes, body)
                    channels = le16(bytes, body + 2)
                    sampleRate = le32(bytes, body + 4)
                    bits = le16(bytes, body + 14)
                }
                "data" -> {
                    dataOffset = body
                    dataSize = size
                    break
                }
            }
            pos = body + size + (size and 1)
        }
        if (format != 1 || channels !in 1..2 || bits !in setOf(8, 16) || sampleRate !in 4_000..192_000) return null
        if (dataOffset < 0 || dataSize <= 0 || dataOffset.toLong() + dataSize > bytes.size.toLong()) return null
        return WavPcm(sampleRate, channels, bits, bytes.copyOfRange(dataOffset, dataOffset + dataSize))
    }

    private fun ascii(bytes: ByteArray, offset: Int, count: Int): String =
        if (offset < 0 || count < 0 || offset + count > bytes.size) "" else String(bytes, offset, count, Charsets.US_ASCII)

    private fun le16(bytes: ByteArray, p: Int): Int =
        (bytes[p].toInt() and 0xff) or ((bytes[p + 1].toInt() and 0xff) shl 8)

    private fun le32(bytes: ByteArray, p: Int): Int =
        (bytes[p].toInt() and 0xff) or
            ((bytes[p + 1].toInt() and 0xff) shl 8) or
            ((bytes[p + 2].toInt() and 0xff) shl 16) or
            ((bytes[p + 3].toInt() and 0xff) shl 24)

    companion object {
        private const val DUCK_FACTOR = 0.20f
        private const val HOST_INTERRUPT_FOCUS = 1
        private const val HOST_INTERRUPT_FOCUS_PERMANENT = 2
        private const val HOST_INTERRUPT_LIFECYCLE = 3
    }
}
