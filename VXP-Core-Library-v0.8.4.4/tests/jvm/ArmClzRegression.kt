package vxpcore

import java.io.File

fun main() {
    val mem = GuestMemory()
    mem.map(0x1000, 0x1000, read = true, write = true, exec = true)
    val rt = MreRuntime(mem, File("/mnt/data/vxp-clz-regression"))
    val cpu = ArmCpu(mem, rt)

    // CLZ r5,r5 = 0xE16F5F15 (same form used by CatBox compatibility code).
    mem.write32(0x1000, 0xE16F5F15.toInt(), force = true)
    cpu.reset(0x1000)
    cpu.r[5] = 0x00000008
    cpu.step()
    check(cpu.r[5] == 28) { "CLZ 0x8 expected 28, got ${cpu.r[5]}" }

    cpu.r[15] = 0x1000
    cpu.r[5] = 0
    cpu.step()
    check(cpu.r[5] == 32) { "CLZ 0 expected 32, got ${cpu.r[5]}" }

    println("[OK] ARM CLZ regression: clz(0x8)=28, clz(0)=32")
}
