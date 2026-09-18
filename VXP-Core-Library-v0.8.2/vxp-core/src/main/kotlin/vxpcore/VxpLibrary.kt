package vxpcore

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Public, UI-free API for embedding the Kotlin VXP runtime. */
enum class VxpBackendType { ELF_ARM, RAW_ARM_ZLIB, FLASH_LITE, UNKNOWN }

enum class VxpKey(val mreCode: Int) {
    UP(-1), DOWN(-2), LEFT(-3), RIGHT(-4), OK(-5),
    LEFT_SOFT(-6), RIGHT_SOFT(-7), CLEAR(-8), BACK(-9),
    STAR(42), POUND(35), NUM0(48), NUM1(49), NUM2(50), NUM3(51), NUM4(52),
    NUM5(53), NUM6(54), NUM7(55), NUM8(56), NUM9(57);

    companion object {
        /** Mapping used by the existing Nokia 225 UI/controller. */
        fun fromLegacy(code: Int): VxpKey? = when (code) {
            1 -> UP; 2 -> DOWN; 3 -> LEFT; 4 -> RIGHT; 5 -> OK
            6 -> LEFT_SOFT; 7 -> RIGHT_SOFT; 10 -> CLEAR
            42 -> STAR; 35 -> POUND
            in 48..57 -> entries.firstOrNull { it.mreCode == code }
            else -> null
        }
    }
}

enum class VxpKeyAction(val mreEvent: Int) {
    UP(MreEventId.VM_KEY_EVENT_UP),
    DOWN(MreEventId.VM_KEY_EVENT_DOWN),
    LONG_PRESS(MreEventId.VM_KEY_EVENT_LONG_PRESS),
    REPEAT(MreEventId.VM_KEY_EVENT_REPEAT)
}

sealed interface VxpSessionState {
    data object Idle : VxpSessionState
    data class Starting(val backend: VxpBackendType) : VxpSessionState
    data class Running(val backend: VxpBackendType) : VxpSessionState
    data class Stopped(val result: VxpRunResult?) : VxpSessionState
    data class Failed(val backend: VxpBackendType, val message: String, val cause: Throwable? = null) : VxpSessionState
}

data class VxpRunResult(
    val backend: VxpBackendType,
    val exitCode: Int,
    val instructions: Long,
    val frames: Long,
    val events: Long,
    val timerCallbacks: Long,
    val timedOut: Boolean,
    val resolvedSymbols: List<String> = emptyList(),
    val stubbedSymbols: List<String> = emptyList()
)

data class VxpSessionOptions(
    val maxInstructionsPerCallback: Long = 20_000_000L,
    /** 0 = no host timeout; the app runs until stop/exit. */
    val maxRuntimeMs: Long = 0L,
    val trace: Boolean = false
)

interface VxpCoreListener {
    fun onState(state: VxpSessionState) {}
    fun onFrame(frame: FrameSnapshot) {}
    fun onResult(result: VxpRunResult) {}
}

/**
 * ARM/RAW/ELF session. Flash Lite is implemented by the optional vxp-core-android
 * host module because SWF JPEG/shape rendering needs a platform image backend.
 *
 * No Android UI, JNI, NDK or native code is used here.
 */
class VxpSession(
    private val bytes: ByteArray,
    private val fileName: String,
    private val storageRoot: File,
    private val listener: VxpCoreListener = object : VxpCoreListener {},
    private val options: VxpSessionOptions = VxpSessionOptions(),
    private val textRasterizer: TextRasterizer = BitmapTextRasterizer()
) : AutoCloseable {
    private val engine = VxpCoreEngine(storageRoot, textRasterizer, options.trace)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null
    @Volatile private var lastResult: VxpRunResult? = null

    val backend: VxpBackendType = engine.detect(bytes).toPublicBackend()
    val isRunning: Boolean get() = engine.isRunning
    val instructions: Long get() = engine.instructions
    val result: VxpRunResult? get() = lastResult

    fun start() {
        check(!closed.get()) { "Session is closed" }
        check(started.compareAndSet(false, true)) { "Session already started" }
        listener.onState(VxpSessionState.Starting(backend))
        val worker = Thread({
            try {
                if (backend == VxpBackendType.FLASH_LITE) {
                    error("FLASH_LITE requires the vxp-core-android host module")
                }
                listener.onState(VxpSessionState.Running(backend))
                val r = engine.run(
                    bytes = bytes,
                    fileName = fileName,
                    maxInstructionsPerCallback = options.maxInstructionsPerCallback,
                    maxRuntimeMs = options.maxRuntimeMs,
                    onFrame = listener::onFrame
                ).toPublicResult()
                lastResult = r
                listener.onResult(r)
                listener.onState(VxpSessionState.Stopped(r))
            } catch (t: Throwable) {
                listener.onState(VxpSessionState.Failed(backend, t.message ?: t::class.java.simpleName, t))
            }
        }, "VXP-Core-$fileName")
        worker.isDaemon = true
        thread = worker
        worker.start()
    }

    fun join(timeoutMs: Long = 0L) {
        val t = thread ?: return
        if (timeoutMs <= 0) t.join() else t.join(timeoutMs)
    }

    fun stop(exitCode: Int = 0) = engine.requestStop(exitCode)

    fun key(key: VxpKey, action: VxpKeyAction) {
        engine.postKeyboardEvent(action.mreEvent, key.mreCode)
    }
    fun keyDown(key: VxpKey) = key(key, VxpKeyAction.DOWN)
    fun keyUp(key: VxpKey) = key(key, VxpKeyAction.UP)
    fun keyRepeat(key: VxpKey) = key(key, VxpKeyAction.REPEAT)

    fun penDown(x: Int, y: Int) = engine.postPenEvent(MreEventId.VM_PEN_EVENT_DOWN, x, y)
    fun penMove(x: Int, y: Int) = engine.postPenEvent(MreEventId.VM_PEN_EVENT_MOVE, x, y)
    fun penUp(x: Int, y: Int) = engine.postPenEvent(MreEventId.VM_PEN_EVENT_UP, x, y)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        engine.requestStop(0)
        thread?.interrupt()
    }
}

object VxpCoreLibrary {
    const val VERSION = "0.8.2"

    fun detect(bytes: ByteArray): VxpBackendType =
        VxpCoreEngine(File(".")).detect(bytes).toPublicBackend()

    fun open(
        bytes: ByteArray,
        fileName: String,
        storageRoot: File,
        listener: VxpCoreListener = object : VxpCoreListener {},
        options: VxpSessionOptions = VxpSessionOptions(),
        textRasterizer: TextRasterizer = BitmapTextRasterizer()
    ): VxpSession = VxpSession(bytes, fileName, storageRoot, listener, options, textRasterizer)
}

internal fun VxpCoreEngine.Backend.toPublicBackend(): VxpBackendType = when (this) {
    VxpCoreEngine.Backend.ELF_ARM -> VxpBackendType.ELF_ARM
    VxpCoreEngine.Backend.RAW_ARM_ZLIB -> VxpBackendType.RAW_ARM_ZLIB
    VxpCoreEngine.Backend.FLASH_LITE -> VxpBackendType.FLASH_LITE
    VxpCoreEngine.Backend.UNKNOWN -> VxpBackendType.UNKNOWN
}

internal fun VxpCoreEngine.Result.toPublicResult(): VxpRunResult = VxpRunResult(
    backend = backend.toPublicBackend(),
    exitCode = exitCode,
    instructions = instructions,
    frames = frames,
    events = events,
    timerCallbacks = timerCallbacks,
    timedOut = timedOut,
    resolvedSymbols = resolvedSymbols,
    stubbedSymbols = stubbedSymbols
)
