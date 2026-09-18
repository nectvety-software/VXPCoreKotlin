package vxpcore

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Host-neutral audio bridge for the MRE compatibility layer.
 *
 * vxp-core never imports android.media.*. A host may provide an implementation
 * (vxp-core-android does) while JVM/headless runs use [StateOnlyMreAudioHost].
 */
enum class MreAudioKind { AUDIO, MIDI }

enum class MreAudioPlaybackState { STOPPED, PLAYING, PAUSED }

enum class MreAudioHostEventType {
    COMPLETED,
    ERROR,
    /** Playback was interrupted by the host audio system (focus/lifecycle). */
    INTERRUPTED,
    /** Playback resumed after a host audio-system interruption. */
    RESUMED
}

data class MreAudioHostEvent(
    val playbackId: Int,
    val kind: MreAudioKind,
    val type: MreAudioHostEventType,
    val code: Int = 0
)

sealed interface MreAudioSource {
    data class Bytes(val bytes: ByteArray, val formatHint: Int = 0) : MreAudioSource
    data class SandboxFile(val file: File, val guestPath: String, val formatHint: Int = 0) : MreAudioSource
}

data class MreAudioRequest(
    val playbackId: Int,
    val kind: MreAudioKind,
    val source: MreAudioSource,
    val loop: Boolean = false,
    val volume: Float = 1f,
    /** Optional compatibility start offset. Hosts clamp it to the media duration. */
    val startPositionMs: Int = 0
)

data class MreAudioSnapshot(
    val state: MreAudioPlaybackState = MreAudioPlaybackState.STOPPED,
    val playbackId: Int = 0,
    val kind: MreAudioKind = MreAudioKind.AUDIO,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val volume: Float = 1f,
    val loop: Boolean = false
)

interface MreAudioHost : AutoCloseable {
    /** Return true only when the host accepted the request. */
    fun play(request: MreAudioRequest, eventSink: (MreAudioHostEvent) -> Unit): Boolean
    fun pause(kind: MreAudioKind? = null): Boolean
    fun resume(kind: MreAudioKind? = null): Boolean
    fun stop(kind: MreAudioKind? = null): Boolean
    fun stopAll(): Boolean = stop(null)
    fun setVolume(volume: Float): Boolean

    /** Optional accurate seek support. Default keeps third-party hosts source-compatible. */
    fun seekTo(positionMs: Int, kind: MreAudioKind? = null): Boolean = false

    /** Optional loop-state mutation for a currently active playback. */
    fun setLooping(loop: Boolean, kind: MreAudioKind? = null): Boolean = false

    /**
     * Lifecycle hooks used by platform adapters. These are intentionally host-only;
     * they do not expose Android types or platform state to the guest runtime.
     */
    fun onHostPause() = Unit
    fun onHostResume() = Unit

    fun snapshot(): MreAudioSnapshot
    override fun close() { stopAll() }
}

/**
 * Headless/JVM compatibility host. It tracks lifecycle but deliberately emits no
 * sound and no synthetic completion event. This keeps the core usable in deterministic
 * tests while Android/Desktop hosts can opt into real playback explicitly.
 */
class StateOnlyMreAudioHost : MreAudioHost {
    private val lock = Any()
    private var state = MreAudioSnapshot()

    override fun play(request: MreAudioRequest, eventSink: (MreAudioHostEvent) -> Unit): Boolean = synchronized(lock) {
        val duration = when (val source = request.source) {
            is MreAudioSource.Bytes -> MreAudioProbe.durationMs(source.bytes, request.kind)
            is MreAudioSource.SandboxFile -> runCatching {
                if (source.file.isFile && source.file.length() <= 32L * 1024L * 1024L) {
                    MreAudioProbe.durationMs(source.file.readBytes(), request.kind)
                } else 0
            }.getOrDefault(0)
        }
        val start = request.startPositionMs.coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it }
        state = MreAudioSnapshot(
            state = MreAudioPlaybackState.PLAYING,
            playbackId = request.playbackId,
            kind = request.kind,
            positionMs = start,
            durationMs = duration,
            volume = request.volume.coerceIn(0f, 1f),
            loop = request.loop
        )
        true
    }

    override fun pause(kind: MreAudioKind?): Boolean = synchronized(lock) {
        if (state.state != MreAudioPlaybackState.PLAYING || (kind != null && state.kind != kind)) return@synchronized false
        state = state.copy(state = MreAudioPlaybackState.PAUSED)
        true
    }

    override fun resume(kind: MreAudioKind?): Boolean = synchronized(lock) {
        if (state.state != MreAudioPlaybackState.PAUSED || (kind != null && state.kind != kind)) return@synchronized false
        state = state.copy(state = MreAudioPlaybackState.PLAYING)
        true
    }

    override fun stop(kind: MreAudioKind?): Boolean = synchronized(lock) {
        if (state.state == MreAudioPlaybackState.STOPPED) return@synchronized true
        if (kind != null && state.kind != kind) return@synchronized false
        state = state.copy(state = MreAudioPlaybackState.STOPPED, positionMs = 0)
        true
    }

    override fun setVolume(volume: Float): Boolean = synchronized(lock) {
        state = state.copy(volume = volume.coerceIn(0f, 1f))
        true
    }

    override fun seekTo(positionMs: Int, kind: MreAudioKind?): Boolean = synchronized(lock) {
        if (state.state == MreAudioPlaybackState.STOPPED || positionMs < 0 || (kind != null && state.kind != kind)) {
            return@synchronized false
        }
        val position = if (state.durationMs > 0) positionMs.coerceAtMost(state.durationMs) else positionMs
        state = state.copy(positionMs = position)
        true
    }

    override fun setLooping(loop: Boolean, kind: MreAudioKind?): Boolean = synchronized(lock) {
        if (state.state == MreAudioPlaybackState.STOPPED || (kind != null && state.kind != kind)) return@synchronized false
        state = state.copy(loop = loop)
        true
    }

    override fun snapshot(): MreAudioSnapshot = synchronized(lock) { state }
}

/**
 * Small clean-room duration probe used by the JVM core.
 *
 * It intentionally supports only formats whose timing can be computed from public
 * container structure without any platform codec: PCM/byte-rate WAV and standard
 * MIDI files with PPQN timing. Encoded MP3/AAC/AMR duration is supplied by the real
 * host once playback is prepared.
 */
object MreAudioProbe {
    fun durationMs(bytes: ByteArray, kind: MreAudioKind = MreAudioKind.AUDIO): Int {
        if (bytes.isEmpty()) return 0
        return when {
            kind == MreAudioKind.MIDI || isMidi(bytes) -> midiDurationMs(bytes)
            isWave(bytes) -> wavDurationMs(bytes)
            else -> 0
        }
    }

    private fun isWave(b: ByteArray): Boolean = b.size >= 12 && ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 4) == "WAVE"
    private fun isMidi(b: ByteArray): Boolean = b.size >= 14 && ascii(b, 0, 4) == "MThd"

    private fun wavDurationMs(bytes: ByteArray): Int {
        var pos = 12
        var byteRate = 0L
        var dataSize = -1L
        while (pos + 8 <= bytes.size) {
            val id = ascii(bytes, pos, 4)
            val size = u32le(bytes, pos + 4)
            if (size < 0 || pos + 8L + size > bytes.size.toLong()) return 0
            val body = pos + 8
            when (id) {
                "fmt " -> if (size >= 12) byteRate = u32le(bytes, body + 8)
                "data" -> {
                    dataSize = size
                    if (byteRate > 0) break
                }
            }
            val next = body.toLong() + size + (size and 1L)
            if (next > Int.MAX_VALUE) return 0
            pos = next.toInt()
        }
        if (byteRate <= 0 || dataSize < 0) return 0
        return ((dataSize * 1000L) / byteRate).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    private data class Tempo(val tick: Long, val microsPerQuarter: Int)

    private fun midiDurationMs(bytes: ByteArray): Int {
        if (!isMidi(bytes)) return 0
        val headerLen = u32be(bytes, 4)
        if (headerLen < 6 || 8L + headerLen > bytes.size.toLong()) return 0
        val tracks = u16be(bytes, 10)
        val division = u16be(bytes, 12)
        // SMPTE timing uses a negative high byte; defer it until observed in corpus.
        if (tracks <= 0 || division <= 0 || (division and 0x8000) != 0) return 0

        val tempos = mutableListOf(Tempo(0, 500_000))
        var maxTick = 0L
        var pos = (8L + headerLen).toInt()
        repeat(tracks) {
            if (pos + 8 > bytes.size || ascii(bytes, pos, 4) != "MTrk") return@repeat
            val len = u32be(bytes, pos + 4)
            if (len < 0 || pos + 8L + len > bytes.size.toLong()) return 0
            val end = (pos + 8L + len).toInt()
            var p = pos + 8
            var tick = 0L
            var runningStatus = 0
            while (p < end) {
                val delta = readVarLen(bytes, p, end) ?: return 0
                p = delta.second
                tick += delta.first
                if (p >= end) break
                var status = bytes[p].toInt() and 0xff
                if (status < 0x80) {
                    if (runningStatus == 0) return 0
                    status = runningStatus
                } else {
                    p++
                    if (status < 0xF0) runningStatus = status
                }

                when {
                    status == 0xFF -> {
                        if (p >= end) return 0
                        val type = bytes[p++].toInt() and 0xff
                        val size = readVarLen(bytes, p, end) ?: return 0
                        p = size.second
                        val n = size.first
                        if (n < 0 || p.toLong() + n > end.toLong()) return 0
                        if (type == 0x51 && n == 3L) {
                            val tempo = ((bytes[p].toInt() and 0xff) shl 16) or
                                ((bytes[p + 1].toInt() and 0xff) shl 8) or
                                (bytes[p + 2].toInt() and 0xff)
                            if (tempo > 0) tempos += Tempo(tick, tempo)
                        }
                        p += n.toInt()
                    }
                    status == 0xF0 || status == 0xF7 -> {
                        val size = readVarLen(bytes, p, end) ?: return 0
                        p = size.second
                        if (size.first < 0 || p.toLong() + size.first > end.toLong()) return 0
                        p += size.first.toInt()
                    }
                    status in 0x80..0xEF -> {
                        val high = status and 0xF0
                        val dataCount = if (high == 0xC0 || high == 0xD0) 1 else 2
                        if (p + dataCount > end) return 0
                        p += dataCount
                    }
                    else -> return 0
                }
            }
            if (tick > maxTick) maxTick = tick
            pos = end
        }
        if (maxTick <= 0L) return 0

        val sorted = tempos
            .groupBy { it.tick }
            .map { (tick, entries) -> Tempo(tick, entries.last().microsPerQuarter) }
            .sortedBy { it.tick }
        var currentTick = 0L
        var tempo = 500_000L
        var totalMicros = 0L
        for (event in sorted) {
            if (event.tick > maxTick) break
            if (event.tick > currentTick) {
                totalMicros += ((event.tick - currentTick) * tempo) / division
                currentTick = event.tick
            }
            tempo = event.microsPerQuarter.toLong()
        }
        if (maxTick > currentTick) totalMicros += ((maxTick - currentTick) * tempo) / division
        return (totalMicros / 1000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun readVarLen(bytes: ByteArray, start: Int, end: Int): Pair<Long, Int>? {
        var p = start
        var value = 0L
        repeat(4) {
            if (p >= end) return null
            val b = bytes[p++].toInt() and 0xff
            value = (value shl 7) or (b and 0x7f).toLong()
            if ((b and 0x80) == 0) return value to p
        }
        return null
    }

    private fun ascii(bytes: ByteArray, offset: Int, count: Int): String =
        if (offset < 0 || count < 0 || offset + count > bytes.size) "" else String(bytes, offset, count, Charsets.US_ASCII)

    private fun u16be(bytes: ByteArray, p: Int): Int =
        if (p < 0 || p + 2 > bytes.size) -1 else ((bytes[p].toInt() and 0xff) shl 8) or (bytes[p + 1].toInt() and 0xff)

    private fun u32be(bytes: ByteArray, p: Int): Long {
        if (p < 0 || p + 4 > bytes.size) return -1
        return ((bytes[p].toLong() and 0xffL) shl 24) or
            ((bytes[p + 1].toLong() and 0xffL) shl 16) or
            ((bytes[p + 2].toLong() and 0xffL) shl 8) or
            (bytes[p + 3].toLong() and 0xffL)
    }

    private fun u32le(bytes: ByteArray, p: Int): Long {
        if (p < 0 || p + 4 > bytes.size) return -1
        return (bytes[p].toLong() and 0xffL) or
            ((bytes[p + 1].toLong() and 0xffL) shl 8) or
            ((bytes[p + 2].toLong() and 0xffL) shl 16) or
            ((bytes[p + 3].toLong() and 0xffL) shl 24)
    }
}

/** Allocates non-zero playback IDs without exposing host handles to guest code. */
internal class MreAudioIdAllocator {
    private val next = AtomicInteger(1)
    fun nextId(): Int {
        while (true) {
            val value = next.getAndUpdate { if (it == Int.MAX_VALUE) 1 else it + 1 }
            if (value != 0) return value
        }
    }
}
