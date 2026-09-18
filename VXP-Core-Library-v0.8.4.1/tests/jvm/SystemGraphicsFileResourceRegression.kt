import vxpcore.*
import java.io.File

private const val SCRATCH = 0x70000000

private fun putUcs2(memory: GuestMemory, address: Int, value: String) {
    value.forEachIndexed { i, ch -> memory.write16(address + i * 2, ch.code) }
    memory.write16(address + value.length * 2, 0)
}
private fun putAscii(memory: GuestMemory, address: Int, value: String) {
    value.forEachIndexed { i, ch -> memory.write8(address + i, ch.code) }
    memory.write8(address + value.length, 0)
}
private fun call(memory: GuestMemory, rt: MreRuntime, cpu: ArmCpu, name: String, vararg args: Int): Int {
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

fun main() {
    val root = File("/mnt/data/VXP-Core-Library-v0.8.4.1/build/runtime_regression_fs").apply { deleteRecursively(); mkdirs() }
    val memory = GuestMemory()
    memory.map(SCRATCH, 0x10000, read = true, write = true, exec = false)
    val rt = MreRuntime(memory, root)
    val cpu = ArmCpu(memory, rt)
    cpu.reset(MreRuntime.HOST_RETURN_TRAP)

    val observedAliases = listOf(
        "vm_get_tick", "vm_get_sym_entry", "vm_get_removable_driver",
        "vm_reg_key_callback", "vm_reg_system_event_callback", "vm_reg_touch_callback",
        "vm_graphic_get_screen_w", "vm_graphic_get_screen_h", "vm_graphic_get_font_height",
        "vm_graphic_get_image_buffer", "vm_graphic_get_img_buffer", "vm_graphic_get_image_property",
        "vm_graphic_load_img", "vm_graphic_release_image", "vm_graphic_get_text_width",
        "vm_file_get_file_size", "vm_resource_init", "vm_res_load", "vm_sscanf"
    )
    observedAliases.forEach { require(rt.addressOf(it) != null) { "observed compatibility symbol is not first-class: $it" } }

    // SYSTEM aliases + guest heap.
    require(call(memory, rt, cpu, "vm_graphic_get_screen_w") == 240)
    require(call(memory, rt, cpu, "vm_graphic_get_screen_h") == 320)
    val p = call(memory, rt, cpu, "vm_malloc", 64)
    require(p != 0)
    memory.write32(p, 0x12345678)
    require(memory.read32(p) == 0x12345678)
    call(memory, rt, cpu, "vm_free", p)
    require(call(memory, rt, cpu, "vm_get_tick") >= 0)
    putAscii(memory, SCRATCH, "vm_malloc")
    require(call(memory, rt, cpu, "vm_get_sym_entry", SCRATCH) == rt.addressOf("vm_malloc"))
    call(memory, rt, cpu, "vm_reg_system_event_callback", 0x123401)
    call(memory, rt, cpu, "vm_reg_key_callback", 0x123501)
    call(memory, rt, cpu, "vm_reg_touch_callback", 0x123601)
    require(rt.systemCallback == 0x123401 && rt.keyboardCallback == 0x123501 && rt.penCallback == 0x123601)

    // GRAPHICS: canvas/image buffer aliases + real in-place mirror + layer translation.
    val canvas = call(memory, rt, cpu, "vm_graphic_create_canvas", 2, 1)
    require(canvas != 0)
    val buf = call(memory, rt, cpu, "vm_graphic_get_image_buffer", canvas)
    require(buf != 0)
    memory.write16(buf, 0xF800)
    memory.write16(buf + 2, 0x001F)
    require(call(memory, rt, cpu, "vm_graphic_mirror", canvas, 1) == 0)
    require(memory.read16(buf) == 0x001F && memory.read16(buf + 2) == 0xF800)
    val layer = call(memory, rt, cpu, "vm_graphic_create_layer", 0, 0, 8, 8, -1)
    require(layer > 0)
    require(call(memory, rt, cpu, "vm_graphic_get_font_height") > 0)

    // FILE: sandbox create/write/size/copy/truncate/read.
    val pathA = SCRATCH + 0x1000
    val pathB = SCRATCH + 0x1200
    val sizeOut = SCRATCH + 0x1400
    val data = SCRATCH + 0x1500
    putUcs2(memory, pathA, "C:\\save\\a.bin")
    putUcs2(memory, pathB, "C:\\save\\b.bin")
    putUcs2(memory, SCRATCH + 0x1800, "C:\\save")
    require(call(memory, rt, cpu, "vm_file_mkdir", SCRATCH + 0x1800) == 0)
    val fh = call(memory, rt, cpu, "vm_file_open", pathA, MreFileSystem.MODE_WRITE or MreFileSystem.MODE_CREATE_ALWAYS)
    require(fh >= 3)
    memory.writeBytes(data, byteArrayOf(1,2,3,4,5))
    require(call(memory, rt, cpu, "vm_file_write", fh, data, 5, sizeOut) == 0)
    require(memory.read32(sizeOut) == 5)
    require(call(memory, rt, cpu, "vm_file_close", fh) == 0)
    require(call(memory, rt, cpu, "vm_file_get_file_size", pathA, sizeOut) == 0)
    require(memory.read32(sizeOut) == 5)
    val fhRead = call(memory, rt, cpu, "vm_file_open", pathA, MreFileSystem.MODE_READ)
    require(fhRead >= 3)
    val readBuf = SCRATCH + 0x1700
    require(call(memory, rt, cpu, "vm_file_read", fhRead, readBuf, 5, sizeOut) == 0)
    require(memory.read32(sizeOut) == 5)
    require(memory.readBytes(readBuf, 5).contentEquals(byteArrayOf(1,2,3,4,5)))
    require(call(memory, rt, cpu, "vm_file_close", fhRead) == 0)

    // RESOURCE: clean-room named resource blob + init/load aliases.
    val name = "asset"
    val headerSize = name.length + 1 + 8
    val blob = ByteArray(headerSize + 3)
    name.forEachIndexed { i, ch -> blob[i] = ch.code.toByte() }
    blob[name.length] = 0
    fun put32(off: Int, v: Int) { for (i in 0..3) blob[off+i] = (v ushr (8*i)).toByte() }
    put32(name.length + 1, headerSize)
    put32(name.length + 5, 3)
    blob[headerSize] = 0x11; blob[headerSize+1] = 0x22; blob[headerSize+2] = 0x33
    rt.installRawResources(blob)
    require(call(memory, rt, cpu, "vm_resource_init") == 0)
    val resName = SCRATCH + 0x2000
    putAscii(memory, resName, name)
    val resPtr = call(memory, rt, cpu, "vm_res_load", resName, sizeOut)
    require(resPtr != 0 && memory.read32(sizeOut) == 3)
    require(memory.read8(resPtr) == 0x11 && memory.read8(resPtr+2) == 0x33)

    // libc-style helper used by Whisk3D-style guests.
    val input = SCRATCH + 0x2200; val fmt = SCRATCH + 0x2300; val outInt = SCRATCH + 0x2400
    putAscii(memory, input, "123")
    putAscii(memory, fmt, "%d")
    require(call(memory, rt, cpu, "vm_sscanf", input, fmt, outInt) == 1)
    require(memory.read32(outInt) == 123)

    rt.close()
    println("[OK] v0.8.4.1 SYSTEM/GRAPHICS/FILE_RESOURCE regression passed")
}
