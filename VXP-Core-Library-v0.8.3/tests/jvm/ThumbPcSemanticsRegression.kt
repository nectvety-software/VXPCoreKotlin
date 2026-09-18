import vxpcore.*
import java.io.File

fun main() {
    val mem=GuestMemory(); mem.map(0x1000,0x2000,true,true,true)
    val rt=MreRuntime(mem,File("/mnt/data/VXP-Core-Library-v0.8.2/build/thumb-pc-fs"))
    val cpu=ArmCpu(mem,rt,false)
    // ldr r3,[pc,#0] at 0x1000 loads literal from 0x1004, then add r3,pc at 0x1002.
    // Architectural PC for the ADD must be 0x1006 (current+4), not internal r15=0x1004.
    mem.write16(0x1000,0x4b00,force=true)
    mem.write16(0x1002,0x447b,force=true) // add r3, pc
    mem.write32(0x1004,0x20,force=true)
    cpu.reset(0x1001)
    cpu.step(); cpu.step()
    check(cpu.r[3] == 0x1026) { "r3=0x${cpu.r[3].toUInt().toString(16)} expected 0x1026" }
    println("[OK] Thumb high-register PC semantics: ADD r3,PC used current+4")
}
