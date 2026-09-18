package vxpcore

import java.io.File

/** Regression for ARM Operand2 reads of PC (architectural PC = instruction+8). */
fun main() {
    val memory = GuestMemory()
    val base = 0x00100000
    memory.map(base, 0x1000, read = true, write = true, exec = true)
    // ADD pc, r12, pc  (E08CF00F). With instruction at base, PC operand is base+8.
    memory.write32(base, 0xE08CF00F.toInt())
    val runtime = MreRuntime(memory, File("/mnt/data/arm_pc_operand_regression_fs"))
    val cpu = ArmCpu(memory, runtime, false)
    cpu.reset(base)
    cpu.r[12] = 0x100
    cpu.step()
    check(cpu.r[15] == base + 8 + 0x100) {
        "ARM PC Operand2 wrong: got=0x${cpu.r[15].toUInt().toString(16)} expected=0x${(base+0x108).toUInt().toString(16)}"
    }
    println("[OK] ARM Operand2 PC regression: ADD pc,r12,pc -> 0x${cpu.r[15].toUInt().toString(16)}")
}
