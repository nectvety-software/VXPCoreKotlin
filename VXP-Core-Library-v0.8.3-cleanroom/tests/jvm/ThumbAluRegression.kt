import vxpcore.*
import java.io.File

private fun cpuWith(words: IntArray): Triple<GuestMemory, MreRuntime, ArmCpu> {
    val mem = GuestMemory()
    mem.map(0x1000, 0x1000, read = true, write = true, exec = true)
    words.forEachIndexed { i, op -> mem.write16(0x1000 + i * 2, op) }
    val rt = MreRuntime(mem, File("/mnt/data/vxp_thumb_alu_fs"))
    val cpu = ArmCpu(mem, rt, false)
    cpu.reset(0x1001)
    return Triple(mem, rt, cpu)
}

fun main() {
    // NEG r1,r0 = 0x4241 (exact opcode observed in Spider-Man).
    run {
        val (_, rt, cpu) = cpuWith(intArrayOf(0x4241))
        cpu.r[0] = 7
        cpu.step()
        check(cpu.r[1] == -7) { "NEG failed: ${cpu.r[1]}" }
        rt.close()
    }

    // ROR r0,r1: 0x41C8, rotate 0x80000001 right by one.
    run {
        val (_, rt, cpu) = cpuWith(intArrayOf(0x41C8))
        cpu.r[0] = 0x80000001.toInt(); cpu.r[1] = 1
        cpu.step()
        check(cpu.r[0] == 0xC0000000.toInt()) { "ROR failed: 0x${cpu.r[0].toUInt().toString(16)}" }
        rt.close()
    }

    // CMP r1,#0 sets C=1; ADC r0,r1 then wraps FFFFFFFF + 0 + 1 -> 0.
    run {
        val (_, rt, cpu) = cpuWith(intArrayOf(0x2900, 0x4148))
        cpu.r[0] = -1; cpu.r[1] = 0
        cpu.step(); cpu.step()
        check(cpu.r[0] == 0) { "ADC failed: 0x${cpu.r[0].toUInt().toString(16)}" }
        rt.close()
    }

    // CMP r1,#0 sets C=1; SBC r0,r1: 5 - 3 -> 2.
    run {
        val (_, rt, cpu) = cpuWith(intArrayOf(0x2900, 0x4188))
        cpu.r[0] = 5; cpu.r[1] = 3
        cpu.step(); cpu.step()
        check(cpu.r[0] == 2) { "SBC failed: ${cpu.r[0]}" }
        rt.close()
    }

    // CMN r0,r1 must update carry without modifying r0. Follow it with ADC r2,r3.
    run {
        val (_, rt, cpu) = cpuWith(intArrayOf(0x42C8, 0x415A))
        cpu.r[0] = -1; cpu.r[1] = 1; cpu.r[2] = 0; cpu.r[3] = 0
        cpu.step()
        check(cpu.r[0] == -1) { "CMN modified destination" }
        cpu.step()
        check(cpu.r[2] == 1) { "CMN carry/ADC failed: ${cpu.r[2]}" }
        rt.close()
    }

    println("[OK] Thumb ALU regression: ADC/SBC/ROR/NEG/CMN")
}
