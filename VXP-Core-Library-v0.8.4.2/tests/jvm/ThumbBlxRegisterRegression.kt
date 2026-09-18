import vxpcore.*
import java.io.File

fun main() {
    val mem=GuestMemory()
    mem.map(0x1000,0x2000,true,true,true)
    val rt=MreRuntime(mem,File("/mnt/data/VXP-Core-Library-v0.8.2/build/blx-reg-fs"))
    val cpu=ArmCpu(mem,rt,false)
    // 0x4798 = BLX r3 in Thumb-1/ARMv5T.
    mem.write16(0x1000,0x4798,force=true)
    // ARM target: BX LR.
    mem.write32(0x2000,0xE12FFF1E.toInt(),force=true)
    cpu.reset(0x1001)
    cpu.r[3]=0x2000
    cpu.step()
    check(!cpu.thumb) { "BLX register did not switch to ARM" }
    check(cpu.r[15]==0x2000) { "target=${cpu.r[15].toUInt().toString(16)}" }
    check(cpu.r[14]==0x1003) { "LR=${cpu.r[14].toUInt().toString(16)} expected 0x1003" }
    cpu.step()
    check(cpu.thumb && cpu.r[15]==0x1002) { "return failed pc=${cpu.r[15].toUInt().toString(16)} thumb=${cpu.thumb}" }
    println("[OK] Thumb BLX register: LR=0x1003, ARM target, returns to Thumb 0x1002")
}
