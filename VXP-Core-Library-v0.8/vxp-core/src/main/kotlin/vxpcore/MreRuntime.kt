package vxpcore

import java.util.concurrent.ConcurrentLinkedQueue
import java.io.File
import kotlin.math.max

class MreRuntime(
    private val memory: GuestMemory,
    fileSystemRoot: File = File("runtime_fs"),
    textRasterizer: TextRasterizer = BitmapTextRasterizer()
) : AutoCloseable {
    companion object {
        const val API_BASE = 0x01000000
        const val API_SIZE = 0x00010000
        const val HOST_RETURN_TRAP = API_BASE + API_SIZE - 4
        const val HEAP_BASE = 0x02000000
        const val HEAP_SIZE = 16 * 1024 * 1024
        const val STACK_BASE = 0x04000000
        const val STACK_SIZE = 1024 * 1024
        const val RESOURCE_BASE = 0x50000000
        const val RESOURCE_MAX_SIZE = 32 * 1024 * 1024
    }

    data class Api(val name: String, val address: Int, val handler: (ArmCpu) -> Unit)

    data class TimerState(
        val id: Int,
        val intervalMs: Long,
        val callback: Int,
        var nextFireNanos: Long,
        var enabled: Boolean = true
    )

    private val byAddress = linkedMapOf<Int, Api>()
    private val byName = linkedMapOf<String, Api>()
    private val events = ConcurrentLinkedQueue<MreEvent>()
    private val timers = linkedMapOf<Int, TimerState>()
    private var nextTimerId = 1
    private val heap = MreHeap(memory, HEAP_BASE, HEAP_SIZE)
    private val resolvedNames = linkedSetOf<String>()
    private val stubbedNames = linkedSetOf<String>()
    private val systemStrings = linkedMapOf<String, Int>()

    data class NamedResource(val name: String, val offset: Int, val size: Int)

    var rawResourceBlob: ByteArray = ByteArray(0)
        private set
    private var resourceMappedSize = 0
    private var namedResources: List<NamedResource> = emptyList()
    var rawExecutableName: String = "app.vxp"

    val graphics: MreGraphics
    val fileSystem = MreFileSystem(fileSystemRoot)

    var systemCallback: Int = 0
        private set
    var keyboardCallback: Int = 0
        private set
    var penCallback: Int = 0
        private set
    var exitRequested: Boolean = false
    var exitCode: Int = 0

    init {
        memory.map(API_BASE, API_SIZE, read = true, write = false, exec = true)
        memory.map(HEAP_BASE, HEAP_SIZE, read = true, write = true, exec = false)
        memory.map(STACK_BASE, STACK_SIZE, read = true, write = true, exec = false)
        graphics = MreGraphics(memory, 240, 320, textRasterizer = textRasterizer)

        // Core display information.
        api("vm_graphic_get_screen_width") { it.r[0] = graphics.screenWidth }
        api("vm_graphic_get_screen_height") { it.r[0] = graphics.screenHeight }
        api("vm_graphic_get_bits_per_pixel") { it.r[0] = 16 }

        // Layer creation: VMINT vm_graphic_create_layer(x, y, width, height, trans_color)
        // AAPCS passes the fifth argument at [sp].
        api("vm_graphic_create_layer") { cpu ->
            val handle = graphics.createLayer(
                x = arg(cpu, 0),
                y = arg(cpu, 1),
                width = arg(cpu, 2),
                height = arg(cpu, 3),
                transparentColor = arg(cpu, 4)
            )
            cpu.r[0] = handle
            if (cpu.trace) {
                val layer = graphics.layer(handle)
                println("[GFX ] create layer=$handle ${layer?.width}x${layer?.height} buf=0x${(layer?.bufferAddress ?: 0).toUInt().toString(16)}")
            }
        }
        api("vm_graphic_get_layer_buffer") { cpu ->
            cpu.r[0] = graphics.getLayerBuffer(arg(cpu, 0))
        }
        api("vm_graphic_active_layer") { cpu ->
            cpu.r[0] = graphics.activeLayer(arg(cpu, 0))
        }
        api("vm_graphic_set_clip") { cpu ->
            cpu.r[0] = graphics.setClip(arg(cpu, 0), arg(cpu, 1), arg(cpu, 2), arg(cpu, 3))
        }
        api("vm_graphic_reset_clip") { cpu ->
            cpu.r[0] = graphics.resetClip()
        }
        api("vm_graphic_delete_layer") { cpu ->
            cpu.r[0] = graphics.deleteLayer(arg(cpu, 0))
        }

        // Common MRE signature: vm_graphic_flush_layer(VMINT *layers, VMINT count).
        // Some code paths/SDK variants are permissive enough that a single handle may
        // be passed directly; support that as a compatibility fallback.
        api("vm_graphic_flush_layer") { cpu ->
            val pointerOrHandle = arg(cpu, 0)
            val count = arg(cpu, 1)
            val handles = decodeLayerList(pointerOrHandle, count)
            cpu.r[0] = graphics.flushLayers(handles)
            if (cpu.trace) println("[GFX ] flush layers=${handles.joinToString()} frame=${graphics.flushCount}")
        }
        api("vm_graphic_flush_screen") { cpu ->
            cpu.r[0] = graphics.flushActiveLayer()
        }

        api("vm_get_tick_count") { it.r[0] = (System.nanoTime() / 1_000_000L).toInt() }
        api("vm_malloc") { cpu ->
            val requested = arg(cpu, 0)
            cpu.r[0] = heap.malloc(requested)
            if (cpu.trace || (cpu.r[0] == 0 && requested > 0)) {
                traceHeap("malloc", requested, cpu.r[0])
                if (cpu.r[0] == 0 && requested > 0) println("[HEAP] malloc failure callerLR=0x${cpu.r[14].toUInt().toString(16)}")
            }
        }
        // Gameloft/MRE binaries observed in the wild use vm_calloc(size) as a
        // one-argument, zero-initializing allocator rather than ISO C calloc(n,size).
        api("vm_calloc") { cpu ->
            val requested = arg(cpu, 0)
            cpu.r[0] = heap.calloc(requested)
            if (cpu.trace || (cpu.r[0] == 0 && requested > 0)) {
                traceHeap("calloc", requested, cpu.r[0])
                if (cpu.r[0] == 0 && requested > 0) println("[HEAP] calloc failure callerLR=0x${cpu.r[14].toUInt().toString(16)}")
            }
        }
        api("vm_free") { cpu ->
            val ptr = arg(cpu, 0)
            val ok = heap.free(ptr)
            cpu.r[0] = 0
            if (cpu.trace && ptr != 0) println("[HEAP] free ptr=0x${ptr.toUInt().toString(16)} ok=$ok ${heapSummary()}")
        }
        api("vm_realloc") { cpu ->
            val ptr = arg(cpu, 0)
            val requested = arg(cpu, 1)
            cpu.r[0] = heap.realloc(ptr, requested)
            if (cpu.trace || cpu.r[0] == 0) println("[HEAP] realloc ptr=0x${ptr.toUInt().toString(16)} size=$requested -> 0x${cpu.r[0].toUInt().toString(16)} callerLR=0x${cpu.r[14].toUInt().toString(16)} ${heapSummary()}")
        }
        api("vm_reg_sysevt_callback") { cpu ->
            systemCallback = cpu.r[0]
            cpu.r[0] = 0
        }
        api("vm_reg_keyboard_callback") { cpu ->
            keyboardCallback = cpu.r[0]
            cpu.r[0] = 0
        }
        api("vm_reg_pen_callback") { cpu ->
            penCallback = cpu.r[0]
            cpu.r[0] = 0
        }
        api("vm_app_log") { cpu ->
            val p = cpu.r[0]
            val text = runCatching { memory.readCString(p) }.getOrElse { "<bad string @0x${p.toUInt().toString(16)}>" }
            println("[VXP] $text")
        }
        api("vm_exit_app") { cpu ->
            exitCode = cpu.r[0]
            exitRequested = true
            cpu.halted = true
        }
        api("vm_create_timer") { cpu ->
            val intervalMs = max(1, cpu.r[0]).toLong()
            val callback = cpu.r[1]
            if (callback == 0) {
                cpu.r[0] = -1
            } else {
                val id = allocateTimerId()
                val now = System.nanoTime()
                timers[id] = TimerState(
                    id = id,
                    intervalMs = intervalMs,
                    callback = callback,
                    nextFireNanos = now + intervalMs * 1_000_000L
                )
                cpu.r[0] = id
                if (cpu.trace) println("[TIMER] create id=$id interval=${intervalMs}ms callback=0x${callback.toUInt().toString(16)}")
            }
        }
        api("vm_delete_timer") { cpu ->
            val removed = timers.remove(cpu.r[0])
            cpu.r[0] = if (removed != null) 0 else -1
            if (cpu.trace && removed != null) println("[TIMER] delete id=${removed.id}")
        }

        // Frequently used native APIs required by stripped Gameloft RAW_ARM_ZLIB titles.
        api("vm_switch_power_saving_mode") { it.r[0] = 0 }
        api("vm_set_volume") { it.r[0] = 0 }
        // Host has no MRE background player; suspension/resume are successful no-ops.
        api("vm_audio_suspend_bg_play") { it.r[0] = 0 }
        api("vm_audio_resume_bg_play") { it.r[0] = 0 }
        api("vm_get_language") { it.r[0] = 0 } // English compatibility profile
        api("vm_get_language_ssc") { it.r[0] = 0 }
        api("vm_get_system_driver") { it.r[0] = 'C'.code }
        api("vm_get_removeable_driver") { it.r[0] = 'E'.code }
        // Returns free bytes for a UCS2 drive/path (e.g. L"C" or L"E").
        // Gameloft startup code compares the return value directly against the
        // required install/save space, so returning 0 produces its low-storage UI.
        api("vm_get_disk_free_space") { it.r[0] = 128 * 1024 * 1024 }
        api("vm_get_exec_filename") { cpu ->
            val dst = arg(cpu, 0)
            val path = "C:\\$rawExecutableName"
            cpu.r[0] = if (writeUcs2(dst, path)) 0 else -1
        }
        api("vm_get_imei") { cpu ->
            // MRE returns a pointer to a zero-terminated ASCII IMEI. Never expose
            // any host/device identifier; keep a deterministic guest-owned value.
            cpu.r[0] = systemAscii("imei", "000000000000000")
        }
        api("vm_get_vm_tag") { cpu -> cpu.r[0] = systemAscii("vm_tag", "MRE") }
        api("vm_is_support_wifi") { it.r[0] = 0 }
        api("vm_wifi_is_connected") { it.r[0] = 0 }
        api("vm_has_sim_card") { it.r[0] = 1 }
        api("vm_get_sim_card_status") { it.r[0] = 1 }
        api("vm_sim_get_active_sim_card") { it.r[0] = 0 }
        api("vm_set_active_sim_card") { it.r[0] = 0 }
        api("vm_graphic_mirror") { cpu ->
            // Signature varies between platform SDK revisions. v0.8 keeps the operation
            // side-effect free until the argument pattern is confidently identified.
            // Registering it directly avoids treating a known compatibility no-op as unresolved.
            cpu.r[0] = 0
        }
        api("vm_graphic_setcolor") { cpu -> cpu.r[0] = graphics.setColor(decodeColorArg(arg(cpu, 0))) }
        api("vm_graphic_fill_rect") { cpu ->
            // Raw ARMCC MRE builds use (buffer, x, y, width, height).
            cpu.r[0] = graphics.fillRectBuffer(arg(cpu, 0), arg(cpu, 1), arg(cpu, 2), arg(cpu, 3), arg(cpu, 4))
        }
        api("vm_graphic_create_canvas") { cpu ->
            cpu.r[0] = graphics.createCanvas(arg(cpu, 0), arg(cpu, 1))
        }
        api("vm_graphic_get_canvas_buffer") { cpu ->
            cpu.r[0] = graphics.getCanvasBuffer(arg(cpu, 0))
        }
        api("vm_graphic_release_canvas") { cpu ->
            cpu.r[0] = graphics.releaseCanvas(arg(cpu, 0))
        }
        api("vm_graphic_canvas_set_trans_color") { cpu ->
            cpu.r[0] = graphics.setCanvasTransparentColor(arg(cpu, 0), decodeColorArg(arg(cpu, 1)))
        }
        api("vm_graphic_blt") { cpu ->
            cpu.r[0] = graphics.blt(
                arg(cpu, 0), arg(cpu, 1), arg(cpu, 2), arg(cpu, 3),
                arg(cpu, 4), arg(cpu, 5), arg(cpu, 6), arg(cpu, 7), arg(cpu, 8)
            )
        }
        api("vm_graphic_line") { cpu -> cpu.r[0] = graphics.drawLine(arg(cpu, 0), arg(cpu, 1), arg(cpu, 2), arg(cpu, 3)) }
        api("vm_graphic_rect") { cpu -> cpu.r[0] = graphics.drawRect(arg(cpu, 0), arg(cpu, 1), arg(cpu, 2), arg(cpu, 3)) }
        api("vm_graphic_get_character_width") { cpu ->
            cpu.r[0] = graphics.characterWidth(arg(cpu, 0))
        }
        api("vm_graphic_get_character_height") { cpu ->
            cpu.r[0] = graphics.characterHeight()
        }
        api("vm_graphic_get_string_width") { cpu ->
            val p = arg(cpu, 0)
            val text = readUcs2Compat(p)
            cpu.r[0] = graphics.stringWidth(text)
            if (cpu.trace) println("[TEXT] width=${cpu.r[0]} '$text'")
        }
        api("vm_graphic_get_string_height") { cpu ->
            val p = arg(cpu, 0)
            val text = readUcs2Compat(p)
            cpu.r[0] = graphics.stringHeight(text)
            if (cpu.trace) println("[TEXT] height=${cpu.r[0]} '$text'")
        }
        api("vm_graphic_set_font") { cpu ->
            cpu.r[0] = graphics.setFont(arg(cpu, 0))
            if (cpu.trace) println("[TEXT] font=${graphics.text.currentFontId()}")
        }
        // Raw ARM Gameloft ABI observed in Spider-Man:
        // textout(buffer, x, y, ucs2, maxWidth, color565)
        api("vm_graphic_textout") { cpu ->
            val value = readUcs2Compat(arg(cpu, 3))
            val maxWidth = arg(cpu, 4).let { if (it <= 0) Int.MAX_VALUE else it }
            val color = decodeColorArg(arg(cpu, 5))
            cpu.r[0] = graphics.drawTextBuffer(arg(cpu, 0), arg(cpu, 1), arg(cpu, 2), value, maxWidth, color)
            if (cpu.trace) println("[TEXT] out buf=0x${arg(cpu,0).toUInt().toString(16)} x=${arg(cpu,1)} y=${arg(cpu,2)} '$value'")
        }
        // Common ABI used by this title: (layer, x, y, ucs2, maxWidth).
        api("vm_graphic_textout_to_layer") { cpu ->
            val value = readUcs2Compat(arg(cpu, 3))
            val maxWidth = arg(cpu, 4).let { if (it <= 0) Int.MAX_VALUE else it }
            cpu.r[0] = graphics.drawTextLayer(arg(cpu, 0), arg(cpu, 1), arg(cpu, 2), value, maxWidth)
            if (cpu.trace) println("[TEXT] out layer=${arg(cpu,0)} x=${arg(cpu,1)} y=${arg(cpu,2)} '$value'")
        }

        // Charset conversion APIs are registered directly so real titles do not
        // fall through the generic compatibility stub path.
        api("vm_ascii_to_ucs2") { cpu -> cpu.r[0] = asciiToUcs2Compat(cpu) }
        api("vm_gb2312_to_ucs2") { cpu -> cpu.r[0] = asciiToUcs2Compat(cpu) }
        api("vm_ucs2_to_ascii") { cpu -> cpu.r[0] = ucs2ToAsciiCompat(cpu) }
        api("vm_chset_convert") { cpu -> cpu.r[0] = charsetConvertCompat(cpu) }

        // Sandboxed file runtime. Paths are MRE UCS2 strings (C:\\... / E:\\...).
        api("vm_file_open") { cpu ->
            val path = readUcs2Compat(arg(cpu, 0))
            val mode = arg(cpu, 1)
            cpu.r[0] = fileSystem.open(path, mode)
            if (cpu.trace) println("[FILE] open '$path' mode=$mode -> ${cpu.r[0]}")
        }
        api("vm_file_close") { cpu -> cpu.r[0] = fileSystem.close(arg(cpu, 0)) }
        api("vm_file_commit") { cpu -> cpu.r[0] = fileSystem.commit(arg(cpu, 0)) }
        api("vm_file_getfilesize") { cpu ->
            val handle = arg(cpu, 0)
            val out = arg(cpu, 1)
            val size = fileSystem.size(handle)
            if (size == null || size > 0xffffffffL || out == 0 || !memory.isMapped(out, 4)) {
                cpu.r[0] = -1
            } else {
                memory.write32(out, size.toInt())
                cpu.r[0] = 0
            }
            if (cpu.trace) println("[FILE] size h=$handle -> ${size ?: -1}")
        }
        api("vm_file_read") { cpu ->
            val handle = arg(cpu, 0); val dst = arg(cpu, 1); val requested = arg(cpu, 2).coerceAtLeast(0); val out = arg(cpu, 3)
            val bytes = if (requested == 0) ByteArray(0) else fileSystem.read(handle, requested)
            val ok = bytes != null && (bytes.isEmpty() || memory.isMapped(dst, bytes.size))
            if (ok) {
                if (bytes!!.isNotEmpty()) memory.writeBytes(dst, bytes)
                if (out != 0 && memory.isMapped(out, 4)) memory.write32(out, bytes.size)
                cpu.r[0] = 0
            } else {
                if (out != 0 && memory.isMapped(out, 4)) memory.write32(out, 0)
                cpu.r[0] = -1
            }
            if (cpu.trace) println("[FILE] read h=$handle req=$requested got=${bytes?.size ?: -1}")
        }
        api("vm_file_write") { cpu ->
            val handle = arg(cpu, 0); val src = arg(cpu, 1); val requested = arg(cpu, 2).coerceAtLeast(0); val out = arg(cpu, 3)
            val valid = requested == 0 || memory.isMapped(src, requested)
            val written = if (valid) fileSystem.write(handle, if (requested == 0) ByteArray(0) else memory.readBytes(src, requested)) else -1
            if (out != 0 && memory.isMapped(out, 4)) memory.write32(out, written.coerceAtLeast(0))
            cpu.r[0] = if (written >= 0) 0 else -1
            if (cpu.trace) println("[FILE] write h=$handle req=$requested wrote=$written")
        }
        api("vm_file_seek") { cpu ->
            val pos = fileSystem.seek(arg(cpu, 0), arg(cpu, 1).toLong(), arg(cpu, 2))
            cpu.r[0] = if (pos != null) 0 else -1
            if (cpu.trace) println("[FILE] seek h=${arg(cpu,0)} off=${arg(cpu,1)} origin=${arg(cpu,2)} -> ${pos ?: -1}")
        }
        api("vm_file_get_attributes") { cpu ->
            val path = readUcs2Compat(arg(cpu, 0))
            cpu.r[0] = fileSystem.attributes(path)
            if (cpu.trace) println("[FILE] attr '$path' -> 0x${cpu.r[0].toUInt().toString(16)}")
        }
        api("vm_file_set_attributes") { cpu ->
            val path = readUcs2Compat(arg(cpu, 0))
            cpu.r[0] = fileSystem.setAttributes(path, arg(cpu, 1))
            if (cpu.trace) println("[FILE] setattr '$path' =0x${arg(cpu,1).toUInt().toString(16)} -> ${cpu.r[0]}")
        }
        api("vm_file_mkdir") { cpu ->
            val path = readUcs2Compat(arg(cpu, 0)); cpu.r[0] = fileSystem.mkdir(path)
            if (cpu.trace) println("[FILE] mkdir '$path' -> ${cpu.r[0]}")
        }
        api("vm_file_delete") { cpu -> val path = readUcs2Compat(arg(cpu, 0)); cpu.r[0] = fileSystem.delete(path) }
        api("vm_file_rmdir") { cpu -> val path = readUcs2Compat(arg(cpu, 0)); cpu.r[0] = fileSystem.rmdir(path) }

        // Raw VXP resource bridge. Gameloft packages expose a small named archive
        // at the beginning of their resource tail (e.g. "mre-2.0"). Offsets in
        // that archive are relative to the start of the raw resource blob.
        api("vm_get_res_header") { cpu ->
            // MRE returns a byte offset/header adjustment used by callers after
            // vm_resource_get_data(), not a host pointer. Raw appended-resource
            // packages used by this Gameloft build have no extra per-resource
            // prefix, therefore the effective adjustment is zero.
            cpu.r[0] = 0
        }
        api("vm_load_resource") { cpu ->
            val namePtr = arg(cpu, 0)
            val sizeOut = arg(cpu, 1)
            val name = runCatching { memory.readCString(namePtr, 256) }.getOrDefault("")
            val entry = namedResources.firstOrNull { it.name == name }
            if (entry == null) {
                if (sizeOut != 0 && memory.isMapped(sizeOut, 4)) memory.write32(sizeOut, 0)
                cpu.r[0] = 0
            } else {
                if (sizeOut != 0 && memory.isMapped(sizeOut, 4)) memory.write32(sizeOut, entry.size)
                cpu.r[0] = RESOURCE_BASE + entry.offset
            }
            if (cpu.trace) println("[RES ] load '$name' -> 0x${cpu.r[0].toUInt().toString(16)} size=${entry?.size ?: 0}")
        }
        api("vm_resource_get_data") { cpu ->
            val dst = arg(cpu, 0)
            val offset = arg(cpu, 1)
            val size = arg(cpu, 2)
            val valid = dst != 0 && offset >= 0 && size >= 0 &&
                offset.toLong() + size.toLong() <= rawResourceBlob.size.toLong() &&
                memory.isMapped(dst, size)
            if (valid) {
                memory.writeBytes(dst, rawResourceBlob, offset, size)
                cpu.r[0] = size
            } else {
                cpu.r[0] = -1
            }
            if (cpu.trace) println("[RES ] read off=0x${offset.toUInt().toString(16)} size=$size dst=0x${dst.toUInt().toString(16)} ok=$valid")
        }

        // Lazy import resolver used by raw ARM VXP import veneers. The guest passes a
        // zero-terminated API name in r0 and receives a callable host trampoline.
        api("__vxp_resolver") { cpu ->
            val name = runCatching { memory.readCString(cpu.r[0], 256) }.getOrDefault("")
            cpu.r[0] = resolveAddressOrStub(name)
            if (cpu.trace) println("[BIND] $name -> 0x${cpu.r[0].toUInt().toString(16)}")
        }
    }

    /** Read an AAPCS integer/pointer argument. r0-r3 then stack words at current sp. */
    private fun arg(cpu: ArmCpu, index: Int): Int {
        require(index >= 0)
        return if (index < 4) cpu.r[index] else memory.read32(cpu.r[13] + (index - 4) * 4)
    }

    private fun decodeLayerList(pointerOrHandle: Int, count: Int): IntArray {
        if (count <= 0) {
            val active = graphics.activeLayerHandle
            return if (active == MreGraphics.VM_GRAPHIC_INVALID_LAYER) intArrayOf() else intArrayOf(active)
        }
        val safeCount = count.coerceAtMost(64)
        if (safeCount == 1 && graphics.layer(pointerOrHandle) != null) return intArrayOf(pointerOrHandle)
        if (!memory.isMapped(pointerOrHandle, safeCount * 4)) {
            return if (graphics.layer(pointerOrHandle) != null) intArrayOf(pointerOrHandle) else intArrayOf()
        }
        return IntArray(safeCount) { i -> memory.read32(pointerOrHandle + i * 4) }
    }

    private fun allocateTimerId(): Int {
        while (nextTimerId == 0 || timers.containsKey(nextTimerId)) nextTimerId++
        return nextTimerId++
    }

    private fun api(name: String, handler: (ArmCpu) -> Unit): Int {
        byName[name]?.let { return it.address }
        val address = API_BASE + byAddress.size * 4
        require(address < HOST_RETURN_TRAP) { "MRE API table exhausted" }
        val api = Api(name, address, handler)
        byAddress[address] = api
        byName[name] = api
        return address
    }

    val resolverAddress: Int get() = byName["__vxp_resolver"]!!.address

    fun addressOf(name: String): Int? = byName[name]?.address

    fun resolvedSymbolNames(): List<String> = resolvedNames.toList()
    fun stubbedSymbolNames(): List<String> = stubbedNames.toList()

    fun resolveAddressOrStub(name: String): Int {
        if (name.isBlank()) return 0
        byName[name]?.let {
            resolvedNames += name
            return it.address
        }
        val address = api(name, compatibilityHandler(name))
        resolvedNames += name
        stubbedNames += name
        return address
    }

    private fun compatibilityHandler(name: String): (ArmCpu) -> Unit = { cpu ->
        if (cpu.trace) println("[STUB] $name")
        when (name) {
            "strtoi" -> {
                val text = runCatching { memory.readCString(cpu.r[0], 64) }.getOrDefault("")
                cpu.r[0] = text.trim().removePrefix("0x").toIntOrNull(if (text.trim().startsWith("0x", true)) 16 else 10) ?: 0
            }
            "vm_graphic_create_layer_ex" -> {
                // Common SDK ABI: (x,y,w,h,transColor,bufferType,externalBuffer).
                val external = arg(cpu, 6)
                val trans = decodeColorArg(arg(cpu, 4))
                cpu.r[0] = if (external != 0 && memory.isMapped(external, 2)) {
                    graphics.createLayerWithBuffer(arg(cpu, 0), arg(cpu, 1), arg(cpu, 2), arg(cpu, 3), trans, external)
                } else {
                    graphics.createLayer(arg(cpu, 0), arg(cpu, 1), arg(cpu, 2), arg(cpu, 3), trans)
                }
            }
            "vm_graphic_fill_rect_ex" -> {
                // Common MRE form: (layer, x, y, width, height), using current color.
                cpu.r[0] = graphics.fillRectOnLayer(arg(cpu, 0), arg(cpu, 1), arg(cpu, 2), arg(cpu, 3), arg(cpu, 4))
            }
            "vm_graphic_rect_ex" -> {
                cpu.r[0] = graphics.drawRectOnLayer(arg(cpu, 0), arg(cpu, 1), arg(cpu, 2), arg(cpu, 3), arg(cpu, 4))
            }
            "vm_ascii_to_ucs2" -> cpu.r[0] = asciiToUcs2Compat(cpu)
            "vm_ucs2_to_ascii" -> cpu.r[0] = ucs2ToAsciiCompat(cpu)
            "vm_gb2312_to_ucs2" -> cpu.r[0] = asciiToUcs2Compat(cpu)
            "vm_chset_convert" -> cpu.r[0] = charsetConvertCompat(cpu)
            "vm_get_exec_filename" -> {
                // Common SDK form writes UCS2 path into caller buffer; return success.
                val dst = cpu.r[0]
                if (dst != 0 && memory.isMapped(dst, 4)) {
                    val ascii = "C:\\$rawExecutableName"
                    var p = dst
                    for (ch in ascii) { if (!memory.isMapped(p, 2)) break; memory.write16(p, ch.code); p += 2 }
                    if (memory.isMapped(p, 2)) memory.write16(p, 0)
                }
                cpu.r[0] = 0
            }
            "vm_get_res_header" -> cpu.r[0] = 0
            "vm_load_resource" -> cpu.r[0] = 0
            "vm_resource_get_data" -> cpu.r[0] = 0
            "vm_audio_resume_bg_play", "vm_audio_suspend_bg_play", "vm_audio_stop", "vm_midi_stop", "vm_set_volume" -> cpu.r[0] = 0
            "vm_midi_get_time" -> cpu.r[0] = 0
            "vm_midi_play_by_bytes" -> cpu.r[0] = 1
            "vm_open_wap_url", "vm_send_sms", "vm_asyn_http_req", "vm_cancel_asyn_http_req" -> cpu.r[0] = -1
            else -> cpu.r[0] = 0
        }
    }

    fun heapStats(): MreHeap.Stats = heap.stats()

    fun validateHeap(): Boolean = heap.validate()

    private fun traceHeap(op: String, requested: Int, ptr: Int) {
        println("[HEAP] $op size=$requested -> 0x${ptr.toUInt().toString(16)} ${heapSummary()}")
    }

    private fun heapSummary(): String {
        val s = heap.stats()
        return "used=${s.allocatedBytes} free=${s.freeBytes} largest=${s.largestFreeBlock} live=${s.liveAllocations} fail=${s.failedAllocations}"
    }

    private fun decodeColorArg(value: Int): Int {
        if (!memory.isMapped(value, 2)) return value and 0xFFFF
        // vm_graphic_setcolor is commonly passed a VM_COLOR/VMUINT16 object by
        // ARMCC-generated code. Prefer a plausible RGB565 word at the pointer.
        val c16 = memory.read16(value)
        if (memory.isMapped(value, 4)) {
            val b0 = memory.read8(value)
            val b1 = memory.read8(value + 1)
            val b2 = memory.read8(value + 2)
            val b3 = memory.read8(value + 3)
            // Some SDKs store ARGB8888. If the bytes look like a populated color,
            // accept either ARGB or RGBA layout and convert to RGB565.
            val alphaFirst = b0 == 0xFF || b0 == 0x00
            if (alphaFirst && (b1 != 0 || b2 != 0 || b3 != 0)) return rgb888To565(b1, b2, b3)
        }
        return c16
    }

    private fun rgb888To565(r: Int, g: Int, b: Int): Int =
        ((r and 0xF8) shl 8) or ((g and 0xFC) shl 3) or ((b and 0xF8) ushr 3)

    private fun readUcs2Compat(address: Int, maxChars: Int = 2048): String {
        if (address == 0 || !memory.isMapped(address, 2)) return ""
        val out = StringBuilder()
        var p = address
        repeat(maxChars) {
            if (!memory.isMapped(p, 2)) return@repeat
            val ch = memory.read16(p)
            p += 2
            if (ch == 0) return out.toString()
            out.append(ch.toChar())
        }
        return out.toString()
    }

    fun installRawResources(blob: ByteArray) {
        rawResourceBlob = blob.copyOf()
        namedResources = parseNamedResources(rawResourceBlob)
        if (blob.isEmpty()) return
        require(blob.size <= RESOURCE_MAX_SIZE) { "Raw VXP resource blob too large: ${blob.size}" }
        if (resourceMappedSize == 0) {
            resourceMappedSize = ((blob.size + 0xfff) and -0x1000).coerceAtLeast(0x1000)
            memory.map(RESOURCE_BASE, resourceMappedSize, read = true, write = false, exec = false)
        } else {
            require(blob.size <= resourceMappedSize) { "Replacement resource blob exceeds mapped arena" }
        }
        memory.writeBytes(RESOURCE_BASE, blob, force = true)
    }

    fun rawNamedResources(): List<NamedResource> = namedResources.toList()

    private fun parseNamedResources(blob: ByteArray): List<NamedResource> {
        if (blob.isEmpty()) return emptyList()
        val out = mutableListOf<NamedResource>()
        var pos = 0
        var firstDataOffset = blob.size
        repeat(128) {
            if (pos >= blob.size || pos >= firstDataOffset) return@repeat
            val end = run {
                var e = pos
                while (e < blob.size && e - pos < 255 && blob[e].toInt() != 0) e++
                e
            }
            if (end >= blob.size || blob[end].toInt() != 0) return out
            if (end == pos) return out // sentinel
            val name = blob.copyOfRange(pos, end).toString(Charsets.US_ASCII)
            pos = end + 1
            if (pos + 8 > blob.size) return out
            val offset = readLe32(blob, pos); pos += 4
            val size = readLe32(blob, pos); pos += 4
            if (offset < 0 || size < 0 || offset.toLong() + size.toLong() > blob.size.toLong()) return out
            firstDataOffset = minOf(firstDataOffset, offset)
            out += NamedResource(name, offset, size)
        }
        return out
    }

    private fun readLe32(bytes: ByteArray, p: Int): Int =
        (bytes[p].toInt() and 0xff) or
            ((bytes[p + 1].toInt() and 0xff) shl 8) or
            ((bytes[p + 2].toInt() and 0xff) shl 16) or
            ((bytes[p + 3].toInt() and 0xff) shl 24)

    private fun asciiToUcs2Compat(cpu: ArmCpu): Int {
        // SDK variants differ in whether the second argument is a byte capacity.
        // Identify source/destination by mapped pointers and use the conventional
        // (dst, dstBytes, src) layout first.
        val dst = cpu.r[0]
        val capacity = cpu.r[1].coerceIn(0, 1 shl 20)
        val src = cpu.r[2]
        if (!memory.isMapped(dst, 2) || !memory.isMapped(src, 1)) return 0
        val maxChars = if (capacity >= 2) (capacity / 2 - 1).coerceAtLeast(0) else 1023
        var s = src
        var d = dst
        var count = 0
        while (count < maxChars && memory.isMapped(s, 1) && memory.isMapped(d, 2)) {
            val ch = memory.read8(s++)
            memory.write16(d, ch)
            d += 2
            if (ch == 0) return count
            count++
        }
        if (memory.isMapped(d, 2)) memory.write16(d, 0)
        return count
    }

    private fun ucs2ToAsciiCompat(cpu: ArmCpu): Int {
        val dst = cpu.r[0]
        val capacity = cpu.r[1].coerceIn(0, 1 shl 20)
        val src = cpu.r[2]
        if (!memory.isMapped(dst, 1) || !memory.isMapped(src, 2)) return 0
        val maxChars = if (capacity > 0) (capacity - 1).coerceAtLeast(0) else 2047
        var s = src
        var d = dst
        var count = 0
        while (count < maxChars && memory.isMapped(s, 2) && memory.isMapped(d, 1)) {
            val ch = memory.read16(s)
            s += 2
            memory.write8(d++, if (ch in 1..255) ch else '?'.code)
            if (ch == 0) return count
            count++
        }
        if (memory.isMapped(d, 1)) memory.write8(d, 0)
        return count
    }

    private fun charsetConvertCompat(cpu: ArmCpu): Int {
        // Gameloft ARM builds call vm_chset_convert(srcCharset,dstCharset,src,dst,...).
        val srcCharset = cpu.r[0]
        val dstCharset = cpu.r[1]
        val src = cpu.r[2]
        val dst = cpu.r[3]
        if (!memory.isMapped(src, 1) || !memory.isMapped(dst, 1)) return -1

        // Character-set IDs used by this game are adjacent (0x25/0x26). Determine
        // direction from source bytes as an additional guard, then always terminate.
        val sourceLooksUcs2 = memory.isMapped(src, 4) && memory.read8(src + 1) == 0 && memory.read8(src + 3) == 0
        val toUcs2 = dstCharset == 0x26 || (!sourceLooksUcs2 && dstCharset != srcCharset)
        var count = 0
        if (toUcs2) {
            var s = src
            var d = dst
            while (count < 2047 && memory.isMapped(s, 1) && memory.isMapped(d, 2)) {
                val ch = memory.read8(s++)
                memory.write16(d, ch)
                d += 2
                if (ch == 0) break
                count++
            }
            if (memory.isMapped(dst + count * 2, 2)) memory.write16(dst + count * 2, 0)
        } else {
            var s = src
            var d = dst
            while (count < 2047 && memory.isMapped(s, 2) && memory.isMapped(d, 1)) {
                val ch = memory.read16(s)
                s += 2
                memory.write8(d++, if (ch in 1..255) ch else '?'.code)
                if (ch == 0) break
                count++
            }
            if (memory.isMapped(dst + count, 1)) memory.write8(dst + count, 0)
        }
        if (cpu.trace) println("[CHAR] convert $srcCharset->$dstCharset count=$count src=0x${src.toUInt().toString(16)} dst=0x${dst.toUInt().toString(16)}")
        return 0
    }
    private fun systemAscii(key: String, value: String): Int {
        systemStrings[key]?.let { return it }
        val ptr = heap.malloc(value.length + 1)
        if (ptr == 0) return 0
        value.forEachIndexed { i, c -> memory.write8(ptr + i, c.code) }
        memory.write8(ptr + value.length, 0)
        systemStrings[key] = ptr
        return ptr
    }

    private fun writeUcs2(address: Int, value: String): Boolean {
        if (address == 0) return false
        val bytes = (value.length + 1) * 2
        if (!memory.isMapped(address, bytes)) return false
        var p = address
        for (ch in value) { memory.write16(p, ch.code); p += 2 }
        memory.write16(p, 0)
        return true
    }

    override fun close() { fileSystem.close() }

    fun isApiAddress(pc: Int): Boolean = byAddress.containsKey(pc and -2)

    fun dispatch(cpu: ArmCpu): Boolean {
        val pc = cpu.r[15] and -2
        val api = byAddress[pc] ?: return false
        if (cpu.trace) {
            println(
                "[MRE] ${api.name}(r0=0x${cpu.r[0].toUInt().toString(16)}, " +
                    "r1=0x${cpu.r[1].toUInt().toString(16)}, r2=0x${cpu.r[2].toUInt().toString(16)}, " +
                    "r3=0x${cpu.r[3].toUInt().toString(16)})"
            )
        }
        api.handler(cpu)
        if (!cpu.halted) {
            val ret = cpu.r[14]
            cpu.thumb = (ret and 1) != 0
            cpu.r[15] = ret and -2
        }
        return true
    }

    fun postSystemEvent(message: Int, param: Int = 0) {
        events.add(MreEvent.System(message, param))
    }

    fun postKeyboardEvent(eventType: Int, keyCode: Int) {
        events.add(MreEvent.Keyboard(eventType, keyCode))
    }

    fun postPenEvent(eventType: Int, x: Int, y: Int) {
        events.add(MreEvent.Pen(eventType, x, y))
    }

    fun pollEvent(): MreEvent? = events.poll()

    fun enqueueDueTimers(nowNanos: Long = System.nanoTime()) {
        if (timers.isEmpty()) return
        val snapshot = timers.values.toList()
        for (timer in snapshot) {
            if (!timer.enabled || !timers.containsKey(timer.id)) continue
            if (nowNanos < timer.nextFireNanos) continue

            // Keep a repeating timer phase-stable even if the host was briefly late.
            val period = timer.intervalMs * 1_000_000L
            do {
                timer.nextFireNanos += period
            } while (timer.nextFireNanos <= nowNanos)

            events.add(MreEvent.Timer(timer.id, timer.callback))
        }
    }

    fun nanosUntilNextTimer(nowNanos: Long = System.nanoTime()): Long? {
        val next = timers.values.asSequence()
            .filter { it.enabled }
            .minOfOrNull { it.nextFireNanos } ?: return null
        return (next - nowNanos).coerceAtLeast(0L)
    }

    fun timerCount(): Int = timers.size

    fun requestExit(code: Int = 0) {
        exitCode = code
        exitRequested = true
    }

    fun printApiTable() {
        byAddress.values.forEach { println("0x${it.address.toUInt().toString(16).padStart(8, '0')}  ${it.name}") }
    }
}
