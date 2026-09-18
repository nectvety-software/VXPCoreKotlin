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
private fun getUcs2(memory: GuestMemory, address: Int, maxChars: Int = 512): String {
    val out = StringBuilder()
    for (i in 0 until maxChars) {
        val ch = memory.read16(address + i * 2)
        if (ch == 0) break
        out.append(ch.toChar())
    }
    return out.toString()
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
    val root = File("/mnt/data/VXP-Core-Library-v0.8.4.2/build/runtime_regression_fs").apply { deleteRecursively(); mkdirs() }
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
        "vm_file_get_file_size", "vm_file_copy", "vm_file_tell", "vm_file_is_eof", "vm_file_get_modify_time",
        "vm_get_default_folder_path", "vm_get_filename", "vm_get_path",
        "vm_resource_init", "vm_res_load", "vm_load_resource_from_file", "vm_resource_get_data_from_file",
        "vm_get_resource_offset", "vm_get_resource_offset_from_file", "vm_res_delete", "vm_res_deinit", "vm_sscanf"
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

    // FILE: sandbox create/write/size/read/seek/append + argument validation.
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
    // Partial read reports actual bytes; a subsequent EOF read succeeds with zero bytes.
    require(call(memory, rt, cpu, "vm_file_read", fhRead, readBuf, 8, sizeOut) == 0)
    require(memory.read32(sizeOut) == 5)
    require(memory.readBytes(readBuf, 5).contentEquals(byteArrayOf(1,2,3,4,5)))
    require(call(memory, rt, cpu, "vm_file_read", fhRead, readBuf, 4, sizeOut) == 0)
    require(memory.read32(sizeOut) == 0)

    // Seek from start/end and reject positions before byte zero without moving the cursor.
    require(call(memory, rt, cpu, "vm_file_seek", fhRead, 2, 0) == 0)
    require(call(memory, rt, cpu, "vm_file_read", fhRead, readBuf, 2, sizeOut) == 0)
    require(memory.readBytes(readBuf, 2).contentEquals(byteArrayOf(3,4)))
    require(call(memory, rt, cpu, "vm_file_seek", fhRead, -1, 2) == 0)
    require(call(memory, rt, cpu, "vm_file_read", fhRead, readBuf, 1, sizeOut) == 0)
    require(memory.read8(readBuf) == 5)
    require(call(memory, rt, cpu, "vm_file_seek", fhRead, 2, 0) == 0)
    require(call(memory, rt, cpu, "vm_file_seek", fhRead, -99, 1) == -1)
    require(call(memory, rt, cpu, "vm_file_read", fhRead, readBuf, 1, sizeOut) == 0)
    require(memory.read8(readBuf) == 3)

    // Negative lengths are errors rather than silently becoming zero-length I/O.
    require(call(memory, rt, cpu, "vm_file_read", fhRead, readBuf, -1, sizeOut) == -1)
    require(memory.read32(sizeOut) == 0)
    // A read-only handle must reject writes.
    memory.write8(data, 0x7F)
    require(call(memory, rt, cpu, "vm_file_write", fhRead, data, 1, sizeOut) == -1)
    require(memory.read32(sizeOut) == 0)
    require(call(memory, rt, cpu, "vm_file_close", fhRead) == 0)

    // Invalid zero-byte handle still fails; zero length does not bypass handle validation.
    require(call(memory, rt, cpu, "vm_file_read", 0x7FFF, 0, 0, sizeOut) == -1)

    // Append is enforced on every write, even if the guest seeks back to the beginning.
    val fhAppend = call(memory, rt, cpu, "vm_file_open", pathA, MreFileSystem.MODE_WRITE or MreFileSystem.MODE_APPEND)
    require(fhAppend >= 3)
    require(call(memory, rt, cpu, "vm_file_seek", fhAppend, 0, 0) == 0)
    memory.write8(data, 9)
    require(call(memory, rt, cpu, "vm_file_write", fhAppend, data, 1, sizeOut) == 0)
    require(memory.read32(sizeOut) == 1)
    require(call(memory, rt, cpu, "vm_file_write", fhAppend, data, -1, sizeOut) == -1)
    require(memory.read32(sizeOut) == 0)
    // Bad bytes-written pointer must fail before touching the file.
    require(call(memory, rt, cpu, "vm_file_write", fhAppend, data, 1, 0x1234) == -1)
    require(call(memory, rt, cpu, "vm_file_close", fhAppend) == 0)
    require(call(memory, rt, cpu, "vm_file_get_file_size", pathA, sizeOut) == 0)
    require(memory.read32(sizeOut) == 6)

    // tell/eof helpers reflect the same cursor used by read/seek.
    val fhTell = call(memory, rt, cpu, "vm_file_open", pathA, MreFileSystem.MODE_READ)
    require(fhTell >= 3)
    require(call(memory, rt, cpu, "vm_file_tell", fhTell) == 0)
    require(call(memory, rt, cpu, "vm_file_is_eof", fhTell) == 0)
    require(call(memory, rt, cpu, "vm_file_seek", fhTell, 0, 2) == 0)
    require(call(memory, rt, cpu, "vm_file_tell", fhTell) == 6)
    require(call(memory, rt, cpu, "vm_file_is_eof", fhTell) == 1)
    require(call(memory, rt, cpu, "vm_file_close", fhTell) == 0)

    // Path helpers never leak host paths and preserve guest C:/E: spelling.
    val pathOut = SCRATCH + 0x1E00
    require(call(memory, rt, cpu, "vm_get_filename", pathA, pathOut) == 0)
    require(getUcs2(memory, pathOut) == "a.bin")
    require(call(memory, rt, cpu, "vm_get_path", pathA, pathOut) == 0)
    require(getUcs2(memory, pathOut) == "C:\\save\\")
    require(call(memory, rt, cpu, "vm_get_default_folder_path", pathOut, 'E'.code) == 0)
    require(getUcs2(memory, pathOut) == "E:\\")

    // Copy/rename edge cases and guest-side attribute metadata.
    require(call(memory, rt, cpu, "vm_file_copy", pathA, pathB) == 0)
    require(call(memory, rt, cpu, "vm_file_get_file_size", pathB, sizeOut) == 0 && memory.read32(sizeOut) == 6)
    require(call(memory, rt, cpu, "vm_file_copy", pathB, pathB) == 0) // same-path is a no-op
    val attrs = MreFileSystem.ATTR_READ_ONLY or MreFileSystem.ATTR_HIDDEN or MreFileSystem.ATTR_SYSTEM or MreFileSystem.ATTR_ARCHIVE
    require(call(memory, rt, cpu, "vm_file_set_attributes", pathB, attrs) == 0)
    val gotAttrs = call(memory, rt, cpu, "vm_file_get_attributes", pathB)
    require((gotAttrs and MreFileSystem.ATTR_READ_ONLY) != 0)
    require((gotAttrs and MreFileSystem.ATTR_HIDDEN) != 0)
    require((gotAttrs and MreFileSystem.ATTR_SYSTEM) != 0)
    require(call(memory, rt, cpu, "vm_file_open", pathB, MreFileSystem.MODE_WRITE) == -1)
    require(call(memory, rt, cpu, "vm_file_set_attributes", pathB, MreFileSystem.ATTR_ARCHIVE) == 0)
    val pathC = SCRATCH + 0x1F00
    putUcs2(memory, pathC, "E:\\moved\\c.bin")
    require(call(memory, rt, cpu, "vm_file_rename", pathB, pathC) == 0)
    require(call(memory, rt, cpu, "vm_file_get_file_size", pathC, sizeOut) == 0 && memory.read32(sizeOut) == 6)
    require(call(memory, rt, cpu, "vm_file_get_file_size", pathB, sizeOut) == -1)
    // Modification time supports both out-parameter and direct-return compatibility forms.
    require(call(memory, rt, cpu, "vm_file_get_modify_time", pathC, sizeOut) == 0)
    require(memory.read32(sizeOut) != 0)
    require(call(memory, rt, cpu, "vm_file_get_modify_time", pathC, 0) != -1)

    val fhVerify = call(memory, rt, cpu, "vm_file_open", pathA, MreFileSystem.MODE_READ)
    require(fhVerify >= 3)
    require(call(memory, rt, cpu, "vm_file_read", fhVerify, readBuf, 6, sizeOut) == 0)
    require(memory.readBytes(readBuf, 6).contentEquals(byteArrayOf(1,2,3,4,5,9)))
    require(call(memory, rt, cpu, "vm_file_close", fhVerify) == 0)

    // Read-only open does not create missing parent paths, and traversal is rejected.
    val missingPath = SCRATCH + 0x1A00
    val traversalPath = SCRATCH + 0x1C00
    putUcs2(memory, missingPath, "C:\\missing\\no.bin")
    putUcs2(memory, traversalPath, "C:\\..\\escape.bin")
    require(call(memory, rt, cpu, "vm_file_open", missingPath, MreFileSystem.MODE_READ) == -1)
    require(!File(root, "C/missing").exists())
    require(call(memory, rt, cpu, "vm_file_open", traversalPath, MreFileSystem.MODE_WRITE or MreFileSystem.MODE_CREATE_ALWAYS) == -1)

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

    // Both resource aliases share identical loading behavior.
    require(call(memory, rt, cpu, "vm_load_resource", resName, sizeOut) == resPtr)
    require(memory.read32(sizeOut) == 3)
    require(call(memory, rt, cpu, "vm_get_resource_offset", resName) == headerSize)

    // Resource-from-file compatibility: named archive, offset lookup, bounded copy and raw slice load.
    val externalPack = File(root, "C/respack.bin").apply { parentFile.mkdirs(); writeBytes(blob) }
    require(externalPack.isFile)
    val packPath = SCRATCH + 0x2080
    putUcs2(memory, packPath, "C:\\respack.bin")
    require(call(memory, rt, cpu, "vm_get_resource_offset_from_file", packPath, resName) == headerSize)
    val extPtr = call(memory, rt, cpu, "vm_load_resource_from_file", packPath, resName, sizeOut)
    require(extPtr != 0 && memory.read32(sizeOut) == 3)
    require(memory.readBytes(extPtr, 3).contentEquals(byteArrayOf(0x11,0x22,0x33)))
    require(call(memory, rt, cpu, "vm_res_delete", extPtr) == 0)
    val fileCopyDst = SCRATCH + 0x20C0
    require(call(memory, rt, cpu, "vm_resource_get_data_from_file", packPath, fileCopyDst, headerSize, 3) == 3)
    require(memory.readBytes(fileCopyDst, 3).contentEquals(byteArrayOf(0x11,0x22,0x33)))
    val slicePtr = call(memory, rt, cpu, "vm_load_resource_from_file", packPath, headerSize, 3, sizeOut)
    require(slicePtr != 0 && memory.read32(sizeOut) == 3)
    require(memory.readBytes(slicePtr, 3).contentEquals(byteArrayOf(0x11,0x22,0x33)))
    require(call(memory, rt, cpu, "vm_res_deinit") == 0)

    // Compatibility fallback for wrappers that hand over an obvious UCS2 resource name.
    val resNameUcs2 = SCRATCH + 0x2040
    putUcs2(memory, resNameUcs2, name)
    require(call(memory, rt, cpu, "vm_res_load", resNameUcs2, sizeOut) == resPtr)
    require(memory.read32(sizeOut) == 3)

    // Missing resource zeroes size; invalid size pointer is rejected without a guest pointer.
    putAscii(memory, resName, "missing")
    memory.write32(sizeOut, 0x77777777)
    require(call(memory, rt, cpu, "vm_res_load", resName, sizeOut) == 0)
    require(memory.read32(sizeOut) == 0)
    putAscii(memory, resName, name)
    require(call(memory, rt, cpu, "vm_res_load", resName, 0x1234) == 0)

    // Raw resource copy enforces [offset, offset+size) bounds and supports zero-byte probes.
    val resCopy = SCRATCH + 0x2100
    require(call(memory, rt, cpu, "vm_resource_get_data", resCopy, headerSize, 3) == 3)
    require(memory.readBytes(resCopy, 3).contentEquals(byteArrayOf(0x11,0x22,0x33)))
    require(call(memory, rt, cpu, "vm_resource_get_data", resCopy, blob.size - 1, 2) == -1)
    require(call(memory, rt, cpu, "vm_resource_get_data", 0, blob.size, 0) == 0)

    // ELF .vm_res parser regression: absolute file offsets become stable RESOURCE_BASE pointers.
    val elfName = "elfasset"
    val elfHeader = elfName.length + 1 + 8
    val elfBlob = ByteArray(elfHeader + 2)
    elfName.forEachIndexed { i, ch -> elfBlob[i] = ch.code.toByte() }
    elfBlob[elfName.length] = 0
    val sectionFileOffset = 0x4000
    fun putElf32(off: Int, v: Int) { for (i in 0..3) elfBlob[off+i] = (v ushr (8*i)).toByte() }
    putElf32(elfName.length + 1, sectionFileOffset + elfHeader)
    putElf32(elfName.length + 5, 2)
    elfBlob[elfHeader] = 0x55; elfBlob[elfHeader+1] = 0x66
    rt.installElfVmResources(elfBlob, sectionFileOffset)
    putAscii(memory, resName, elfName)
    val elfPtr = call(memory, rt, cpu, "vm_load_resource", resName, sizeOut)
    require(elfPtr == MreRuntime.RESOURCE_BASE + elfHeader)
    require(memory.read32(sizeOut) == 2)
    require(memory.readBytes(elfPtr, 2).contentEquals(byteArrayOf(0x55,0x66)))

    // Replacing an archive with a smaller blob clears stale mapped tail bytes.
    rt.installRawResources(ByteArray(4))
    require(memory.read8(MreRuntime.RESOURCE_BASE + elfHeader) == 0)
    putAscii(memory, resName, elfName)
    require(call(memory, rt, cpu, "vm_load_resource", resName, sizeOut) == 0)
    require(memory.read32(sizeOut) == 0)

    // libc-style helper used by Whisk3D-style guests.
    val input = SCRATCH + 0x2200; val fmt = SCRATCH + 0x2300; val outInt = SCRATCH + 0x2400
    putAscii(memory, input, "123")
    putAscii(memory, fmt, "%d")
    require(call(memory, rt, cpu, "vm_sscanf", input, fmt, outInt) == 1)
    require(memory.read32(outInt) == 123)

    rt.close()
    println("[OK] v0.8.4.2 FILE_RESOURCE regression passed")
}
