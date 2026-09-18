package vxpcore

import java.io.File

fun main() {
    val memory = GuestMemory()
    val base = 0x00100000
    memory.map(base, 0x1000, read = true, write = true, exec = true)
    val runtime = MreRuntime(memory, File("/mnt/data/arm_longmul_regression_fs"))
    val cpu = ArmCpu(memory, runtime, false)

    // UMULL r0,r12,r7,r1 : 0xffffffff * 2 = 0x00000001fffffffe
    memory.write32(base, 0xE08C0197.toInt())
    cpu.reset(base)
    cpu.r[7] = -1
    cpu.r[1] = 2
    cpu.step()
    check(cpu.r[0] == 0xfffffffe.toInt()) { "UMULL low wrong: 0x${cpu.r[0].toUInt().toString(16)}" }
    check(cpu.r[12] == 1) { "UMULL high wrong: 0x${cpu.r[12].toUInt().toString(16)}" }

    // SMULL r2,r3,r4,r5 : -2 * 3 = -6.
    memory.write32(base + 4, 0xE0C32594.toInt())
    cpu.reset(base + 4)
    cpu.r[4] = -2
    cpu.r[5] = 3
    cpu.step()
    check(cpu.r[2] == -6) { "SMULL low wrong: ${cpu.r[2]}" }
    check(cpu.r[3] == -1) { "SMULL high wrong: ${cpu.r[3]}" }
    println("[OK] ARM long multiply regression: UMULL + SMULL")
}
