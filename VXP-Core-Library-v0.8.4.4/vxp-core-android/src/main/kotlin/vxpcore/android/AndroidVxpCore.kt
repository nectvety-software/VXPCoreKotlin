package vxpcore.android

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import vxpcore.*

/**
 * Android host facade for the Kotlin-only VXP core. It contains no Activity/View/Compose code.
 * Your existing UI only needs to consume [FrameSnapshot] and forward key/touch events.
 */
object AndroidVxpCore {
    const val VERSION = "0.8.4.4"

    /**
     * Preferred Android entry point for v0.8.4.4+. Supplying a Context enables
     * AudioManager focus/interruption integration while the JVM core stays neutral.
     */
    fun open(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        storageRoot: File,
        listener: VxpCoreListener = object : VxpCoreListener {},
        options: VxpSessionOptions = VxpSessionOptions(),
        textRasterizer: TextRasterizer = AndroidTextRasterizer(),
        audioHost: MreAudioHost? = null
    ): AndroidVxpSession {
        val safe = fileName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "app" }
        val host = audioHost ?: AndroidMreAudioHost(context.applicationContext, File(storageRoot, "_audio_cache/$safe"))
        return AndroidVxpSession(bytes, fileName, storageRoot, listener, options, textRasterizer, host)
    }

    /** Backward-compatible overload; real playback works but AudioManager focus is not available. */
    fun open(
        bytes: ByteArray,
        fileName: String,
        storageRoot: File,
        listener: VxpCoreListener = object : VxpCoreListener {},
        options: VxpSessionOptions = VxpSessionOptions(),
        textRasterizer: TextRasterizer = AndroidTextRasterizer(),
        audioHost: MreAudioHost? = null
    ): AndroidVxpSession {
        val safe = fileName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "app" }
        val host = audioHost ?: AndroidMreAudioHost(File(storageRoot, "_audio_cache/$safe"))
        return AndroidVxpSession(bytes, fileName, storageRoot, listener, options, textRasterizer, host)
    }
}

class AndroidVxpSession internal constructor(
    private val bytes: ByteArray,
    private val fileName: String,
    private val storageRoot: File,
    private val listener: VxpCoreListener,
    private val options: VxpSessionOptions,
    private val textRasterizer: TextRasterizer,
    private val audioHost: MreAudioHost
) : AutoCloseable {
    private val backend = VxpCoreLibrary.detect(bytes)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    @Volatile private var armSession: VxpSession? = null
    @Volatile private var flashSession: FlashLiteAndroidBackend.Session? = null
    @Volatile private var flashThread: Thread? = null
    @Volatile private var flashFrames = 0L

    val detectedBackend: VxpBackendType get() = backend

    fun start() {
        check(!closed.get()) { "Session is closed" }
        check(started.compareAndSet(false, true)) { "Session already started" }
        if (backend != VxpBackendType.FLASH_LITE) {
            val s = VxpCoreLibrary.open(bytes, fileName, storageRoot, listener, options, textRasterizer, audioHost)
            armSession = s
            s.start()
            return
        }

        listener.onState(VxpSessionState.Starting(backend))
        val flash = FlashLiteAndroidBackend.open(bytes, preferMenu = true, trace = options.trace)
        flashSession = flash
        listener.onState(VxpSessionState.Running(backend))
        listener.onFrame(flash.snapshot())
        flashFrames++

        val worker = Thread({
            val delayMs = (1000.0 / flash.frameRate.coerceAtLeast(1.0)).toLong().coerceAtLeast(1L)
            try {
                while (!closed.get()) {
                    Thread.sleep(delayMs)
                    if (flash.playing) {
                        flash.advanceTimeline()
                        listener.onFrame(flash.snapshot())
                        flashFrames++
                    }
                }
            } catch (_: InterruptedException) {
                // Normal shutdown.
            } catch (t: Throwable) {
                listener.onState(VxpSessionState.Failed(backend, t.message ?: "Flash Lite runtime failure", t))
            }
        }, "VXP-Flash-$fileName")
        worker.isDaemon = true
        flashThread = worker
        worker.start()
    }

    fun keyDown(key: VxpKey) {
        armSession?.keyDown(key)
        flashSession?.let {
            if (it.keyDown(key)) {
                listener.onFrame(it.snapshot())
                flashFrames++
            }
        }
    }

    fun keyUp(key: VxpKey) {
        armSession?.keyUp(key)
        flashSession?.let {
            if (it.keyUp(key)) {
                listener.onFrame(it.snapshot())
                flashFrames++
            }
        }
    }

    fun keyRepeat(key: VxpKey) {
        armSession?.keyRepeat(key)
        // Flash Lite key-repeat is represented as another key-down event.
        flashSession?.let {
            if (it.keyDown(key)) {
                listener.onFrame(it.snapshot())
                flashFrames++
            }
        }
    }

    fun keyDownLegacy(code: Int) { VxpKey.fromLegacy(code)?.let(::keyDown) }
    fun keyUpLegacy(code: Int) { VxpKey.fromLegacy(code)?.let(::keyUp) }
    fun keyRepeatLegacy(code: Int) { VxpKey.fromLegacy(code)?.let(::keyRepeat) }

    fun penDown(x: Int, y: Int) = armSession?.penDown(x, y) ?: Unit
    fun penMove(x: Int, y: Int) = armSession?.penMove(x, y) ?: Unit
    fun penUp(x: Int, y: Int) = armSession?.penUp(x, y) ?: Unit

    /** Call from Activity/Fragment onPause to preserve playback position safely. */
    fun onHostPause() {
        armSession?.onHostPause() ?: audioHost.onHostPause()
    }

    /** Call from Activity/Fragment onResume to resume only system-paused playback. */
    fun onHostResume() {
        armSession?.onHostResume() ?: audioHost.onHostResume()
    }

    fun audioSnapshot(): MreAudioSnapshot = audioHost.snapshot()
    fun seekAudioTo(positionMs: Int, kind: MreAudioKind? = null): Boolean = audioHost.seekTo(positionMs, kind)
    fun setAudioLooping(loop: Boolean, kind: MreAudioKind? = null): Boolean = audioHost.setLooping(loop, kind)

    fun stop(exitCode: Int = 0) {
        armSession?.stop(exitCode)
        if (backend == VxpBackendType.FLASH_LITE) close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        armSession?.close()
        runCatching { audioHost.close() }
        flashThread?.interrupt()
        if (backend == VxpBackendType.FLASH_LITE) {
            val result = VxpRunResult(
                backend = backend,
                exitCode = 0,
                instructions = 0,
                frames = flashFrames,
                events = 0,
                timerCallbacks = 0,
                timedOut = false
            )
            listener.onResult(result)
            listener.onState(VxpSessionState.Stopped(result))
        }
    }
}
