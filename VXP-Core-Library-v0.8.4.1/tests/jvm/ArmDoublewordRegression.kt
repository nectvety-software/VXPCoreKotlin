package vxpcore
import java.io.File

fun main() {
    val mem = GuestMemory()
    mem.map(0x1000, 0x2000, true, true, true)
    val rt = MreRuntime(mem, File("/mnt/data/vxp-doubleword-regression"))
    val cpu = ArmCpu(mem, rt)

    // LDRD r2,r3,[r0] ; STRD r2,r3,[r1]
    mem.write32(0x1000, 0xE1C020D0.toInt(), force = true)
    mem.write32(0x1004, 0xE1C120F0.toInt(), force = true)
    mem.write32(0x1800, 0x11223344, force = true)
    mem.write32(0x1804, 0x55667788, force = true)
    cpu.reset(0x1000)
    cpu.r[0] = 0x1800
    cpu.r[1] = 0x1900
    cpu.step()
    check(cpu.r[2] == 0x11223344 && cpu.r[3] == 0x55667788)
    cpu.step()
    check(mem.read32(0x1900) == 0x11223344 && mem.read32(0x1904) == 0x55667788)
    println("[OK] ARM LDRD/STRD regression: 64-bit pair copied correctly")
}
