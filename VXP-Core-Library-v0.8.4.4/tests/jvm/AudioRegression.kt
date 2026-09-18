import vxpcore.*
import java.io.File

private const val AUDIO_SCRATCH = 0x71000000

private fun audioPutUcs2(memory: GuestMemory, address: Int, value: String) {
    value.forEachIndexed { i, ch -> memory.write16(address + i * 2, ch.code) }
    memory.write16(address + value.length * 2, 0)
}

private fun audioCall(memory: GuestMemory, rt: MreRuntime, cpu: ArmCpu, name: String, vararg args: Int): Int {
    val address = rt.addressOf(name) ?: error("missing API $name")
    cpu.r[13] = MreRuntime.STACK_BASE + MreRuntime.STACK_SIZE - 0x100
    for (i in 0 until 4) cpu.r[i] = args.getOrElse(i) { 0 }
    for (i in 4 until args.size) memory.write32(cpu.r[13] + (i - 4) * 4, args[i])
    cpu.r[14] = MreRuntime.HOST_RETURN_TRAP
    cpu.r[15] = address
    cpu.thumb = false
    cpu.halted = false
    cpu.step()
    return cpu.r[0]
}

private fun wav8Mono(sampleRate: Int = 8_000, durationMs: Int = 1_000): ByteArray {
    val frames = sampleRate * durationMs / 1000
    val data = ByteArray(frames) { 0x80.toByte() }
    val out = ByteArray(44 + data.size)
    fun ascii(off: Int, value: String) = value.forEachIndexed { i, c -> out[off + i] = c.code.toByte() }
    fun le16(off: Int, value: Int) { out[off] = value.toByte(); out[off + 1] = (value ushr 8).toByte() }
    fun le32(off: Int, value: Int) {
        out[off] = value.toByte(); out[off + 1] = (value ushr 8).toByte()
        out[off + 2] = (value ushr 16).toByte(); out[off + 3] = (value ushr 24).toByte()
    }
    ascii(0, "RIFF"); le32(4, out.size - 8); ascii(8, "WAVE")
    ascii(12, "fmt "); le32(16, 16); le16(20, 1); le16(22, 1)
    le32(24, sampleRate); le32(28, sampleRate); le16(32, 1); le16(34, 8)
    ascii(36, "data"); le32(40, data.size); data.copyInto(out, 44)
    return out
}

private fun oneSecondMidi(): ByteArray = byteArrayOf(
    'M'.code.toByte(), 'T'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte(),
    0,0,0,6, 0,0, 0,1, 0x01,0xE0.toByte(), // format 0, one track, PPQN=480
    'M'.code.toByte(), 'T'.code.toByte(), 'r'.code.toByte(), 'k'.code.toByte(),
    0,0,0,13,
    0, 0x90.toByte(), 60, 64,
    0x87.toByte(), 0x40, 0x80.toByte(), 60, 64, // delta 960 ticks = 1s at default tempo
    0, 0xFF.toByte(), 0x2F, 0
)

private class FakeAudioHost : MreAudioHost {
    var lastRequest: MreAudioRequest? = null
    var lastSink: ((MreAudioHostEvent) -> Unit)? = null
    private var snap = MreAudioSnapshot()

    override fun play(request: MreAudioRequest, eventSink: (MreAudioHostEvent) -> Unit): Boolean {
        lastRequest = request
        lastSink = eventSink
        val duration = when (val source = request.source) {
            is MreAudioSource.Bytes -> MreAudioProbe.durationMs(source.bytes, request.kind)
            is MreAudioSource.SandboxFile -> MreAudioProbe.durationMs(source.file.readBytes(), request.kind)
        }
        snap = MreAudioSnapshot(
            state = MreAudioPlaybackState.PLAYING,
            playbackId = request.playbackId,
            kind = request.kind,
            positionMs = request.startPositionMs.coerceAtMost(duration.takeIf { it > 0 } ?: Int.MAX_VALUE),
            durationMs = duration,
            volume = request.volume,
            loop = request.loop
        )
        return true
    }
    override fun pause(kind: MreAudioKind?): Boolean {
        if (snap.state != MreAudioPlaybackState.PLAYING || (kind != null && snap.kind != kind)) return false
        snap = snap.copy(state = MreAudioPlaybackState.PAUSED)
        return true
    }
    override fun resume(kind: MreAudioKind?): Boolean {
        if (snap.state != MreAudioPlaybackState.PAUSED || (kind != null && snap.kind != kind)) return false
        snap = snap.copy(state = MreAudioPlaybackState.PLAYING)
        return true
    }
    override fun stop(kind: MreAudioKind?): Boolean {
        if (kind != null && snap.state != MreAudioPlaybackState.STOPPED && snap.kind != kind) return false
        snap = snap.copy(state = MreAudioPlaybackState.STOPPED, positionMs = 0)
        return true
    }
    override fun setVolume(volume: Float): Boolean { snap = snap.copy(volume = volume); return true }
    override fun seekTo(positionMs: Int, kind: MreAudioKind?): Boolean {
        if (snap.state == MreAudioPlaybackState.STOPPED || positionMs < 0 || (kind != null && snap.kind != kind)) return false
        snap = snap.copy(positionMs = if (snap.durationMs > 0) positionMs.coerceAtMost(snap.durationMs) else positionMs)
        return true
    }
    override fun setLooping(loop: Boolean, kind: MreAudioKind?): Boolean {
        if (snap.state == MreAudioPlaybackState.STOPPED || (kind != null && snap.kind != kind)) return false
        snap = snap.copy(loop = loop); return true
    }
    override fun snapshot(): MreAudioSnapshot = snap
    fun emit(type: MreAudioHostEventType, code: Int = 0) {
        val request = lastRequest ?: error("nothing playing")
        if (type == MreAudioHostEventType.COMPLETED || type == MreAudioHostEventType.ERROR) {
            snap = snap.copy(state = MreAudioPlaybackState.STOPPED)
        }
        lastSink?.invoke(MreAudioHostEvent(request.playbackId, request.kind, type, code))
    }
}

fun main() {
    val root = File("/mnt/data/VXP-Core-Library-v0.8.4.4/build/audio_regression_fs").apply { deleteRecursively(); mkdirs() }
    val memory = GuestMemory()
    memory.map(AUDIO_SCRATCH, 0x20000, read = true, write = true, exec = false)
    val host = FakeAudioHost()
    val rt = MreRuntime(memory, root, audioHost = host)
    val cpu = ArmCpu(memory, rt)
    cpu.reset(MreRuntime.HOST_RETURN_TRAP)

    val required = listOf(
        "vm_audio_play_bytes", "vm_audio_play_bytes_no_block", "vm_audio_play_file", "vm_audio_play_file_ex",
        "vm_audio_bytes_duration", "vm_audio_duration", "vm_audio_pause", "vm_audio_resume", "vm_audio_stop", "vm_audio_stop_all",
        "vm_audio_is_app_playing", "vm_audio_get_time", "vm_audio_register_interrupt_callback",
        "vm_audio_clear_interrupt_callback", "vm_set_volume", "vm_audio_set_volume_type", "vm_audio_terminate_background_play",
        "vm_midi_play_by_bytes", "vm_midi_play_by_bytes_ex", "vm_midi_pause", "vm_midi_resume", "vm_midi_stop", "vm_midi_stop_all", "vm_midi_get_time"
    )
    required.forEach { require(rt.addressOf(it) != null) { "audio API not first-class: $it" } }

    val bytesPtr = AUDIO_SCRATCH + 0x1000
    val wav = wav8Mono()
    memory.writeBytes(bytesPtr, wav)
    require(MreAudioProbe.durationMs(wav) == 1_000)
    require(audioCall(memory, rt, cpu, "vm_audio_bytes_duration", bytesPtr, wav.size, 0) == 1_000)

    val callback = 0x123401
    require(audioCall(memory, rt, cpu, "vm_audio_register_interrupt_callback", callback) == 0)
    require(audioCall(memory, rt, cpu, "vm_set_volume", 3) == 0)
    require(kotlin.math.abs(host.snapshot().volume - 0.5f) < 0.001f)

    require(audioCall(memory, rt, cpu, "vm_audio_play_bytes_no_block", bytesPtr, wav.size, 7) == 1)
    val first = host.lastRequest ?: error("play request missing")
    require(first.kind == MreAudioKind.AUDIO)
    require((first.source as MreAudioSource.Bytes).bytes.contentEquals(wav))
    memory.write8(bytesPtr, 0)
    require((first.source as MreAudioSource.Bytes).bytes[0] == 'R'.code.toByte())
    require(audioCall(memory, rt, cpu, "vm_audio_duration") == 1_000)
    require(host.seekTo(375, MreAudioKind.AUDIO))
    require(audioCall(memory, rt, cpu, "vm_audio_get_time") == 375)
    require(audioCall(memory, rt, cpu, "vm_audio_is_app_playing") == 1)
    require(audioCall(memory, rt, cpu, "vm_audio_pause") == 0)
    require(host.snapshot().state == MreAudioPlaybackState.PAUSED)
    require(audioCall(memory, rt, cpu, "vm_audio_resume") == 0)
    require(host.snapshot().state == MreAudioPlaybackState.PLAYING)

    // Focus/lifecycle events use the same safe guest-callback queue as completion.
    host.emit(MreAudioHostEventType.INTERRUPTED)
    var callbackEvent = rt.pollEvent() as? MreEvent.GuestCallback ?: error("interrupt was not marshalled to event loop")
    require(callbackEvent.callback == callback && callbackEvent.args[0] == 2 && callbackEvent.args[1] == first.playbackId)
    host.emit(MreAudioHostEventType.RESUMED)
    callbackEvent = rt.pollEvent() as? MreEvent.GuestCallback ?: error("resume was not marshalled to event loop")
    require(callbackEvent.args[0] == 3 && callbackEvent.args[1] == first.playbackId)

    host.emit(MreAudioHostEventType.COMPLETED)
    callbackEvent = rt.pollEvent() as? MreEvent.GuestCallback ?: error("completion was not marshalled to event loop")
    require(callbackEvent.callback == callback)
    require(callbackEvent.args.size == 2 && callbackEvent.args[0] == 1 && callbackEvent.args[1] == first.playbackId)
    require(audioCall(memory, rt, cpu, "vm_audio_is_app_playing") == 0)

    // File playback receives only a sandbox-resolved host File. Extended profile
    // forwards start offset + loop without weakening the sandbox boundary.
    File(root, "C/audio").mkdirs()
    File(root, "C/audio/test.wav").writeBytes(wav)
    val pathPtr = AUDIO_SCRATCH + 0x7000
    audioPutUcs2(memory, pathPtr, "C:\\audio\\test.wav")
    require(audioCall(memory, rt, cpu, "vm_audio_play_file_ex", pathPtr, 0, 250, 1) == 1)
    val fileRequest = host.lastRequest ?: error("file play request missing")
    val source = fileRequest.source as MreAudioSource.SandboxFile
    require(source.guestPath == "C:\\audio\\test.wav")
    require(source.file.canonicalPath.startsWith(root.canonicalPath))
    require(fileRequest.startPositionMs == 250 && fileRequest.loop)
    require(host.snapshot().positionMs == 250 && host.snapshot().durationMs == 1_000 && host.snapshot().loop)
    require(audioCall(memory, rt, cpu, "vm_audio_stop") == 0)

    // Traversal never reaches the host adapter.
    val previousId = host.lastRequest!!.playbackId
    audioPutUcs2(memory, pathPtr, "C:\\..\\escape.mp3")
    require(audioCall(memory, rt, cpu, "vm_audio_play_file", pathPtr, 0) == -1)
    require(host.lastRequest!!.playbackId == previousId)

    // MIDI duration is computed in JVM-neutral code from SMF tick/tempo data.
    val midiPtr = AUDIO_SCRATCH + 0x9000
    val midi = oneSecondMidi()
    memory.writeBytes(midiPtr, midi)
    require(MreAudioProbe.durationMs(midi, MreAudioKind.MIDI) == 1_000)
    require(audioCall(memory, rt, cpu, "vm_midi_play_by_bytes_ex", midiPtr, midi.size, 0) == 1)
    require(host.lastRequest!!.kind == MreAudioKind.MIDI)
    require(host.snapshot().durationMs == 1_000)
    require(audioCall(memory, rt, cpu, "vm_midi_pause") == 0)
    require(audioCall(memory, rt, cpu, "vm_midi_resume") == 0)
    require(audioCall(memory, rt, cpu, "vm_midi_stop") == 0)

    // Invalid pointers/sizes are rejected before the host sees a new request.
    val lastId = host.lastRequest!!.playbackId
    require(audioCall(memory, rt, cpu, "vm_audio_play_bytes", 0x1234, 16, 0) == -1)
    require(audioCall(memory, rt, cpu, "vm_audio_play_bytes", bytesPtr, -1, 0) == -1)
    require(audioCall(memory, rt, cpu, "vm_audio_bytes_duration", 0x1234, 16, 0) == 0)
    require(host.lastRequest!!.playbackId == lastId)

    // Clearing the interrupt callback suppresses all host event delivery.
    require(audioCall(memory, rt, cpu, "vm_audio_clear_interrupt_callback") == 0)
    memory.writeBytes(midiPtr, midi)
    require(audioCall(memory, rt, cpu, "vm_midi_play_by_bytes", midiPtr, midi.size, 0) == 1)
    host.emit(MreAudioHostEventType.COMPLETED)
    require(rt.pollEvent() == null)

    rt.close()
    println("[OK] v0.8.4.4 AUDIO playback-accuracy regression passed")
}
