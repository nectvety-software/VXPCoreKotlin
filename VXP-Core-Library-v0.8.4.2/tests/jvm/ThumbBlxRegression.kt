import vxpcore.*
import java.io.File
fun main(){
 val mem=GuestMemory(); mem.map(0x1000,0x2000,read=true,write=true,exec=true)
 mem.write16(0x1000,0xF001); mem.write16(0x1002,0xE9A4)
 val rt=MreRuntime(mem,File("/mnt/data/VXP-Core-Library-v0.8.1/build/thumb_blx_fs"))
 val cpu=ArmCpu(mem,rt,false); cpu.reset(0x1001); cpu.step(); check(cpu.thumb); cpu.step()
 check(!cpu.thumb) {"BLX must switch to ARM"}; check(cpu.r[15]==0x234c){"target=0x${cpu.r[15].toString(16)}"};check(cpu.r[14]==0x1005){"lr=0x${cpu.r[14].toString(16)}"}
 println("[OK] Thumb BLX immediate F001 E9A4 -> ARM target=0x${cpu.r[15].toString(16)} LR=0x${cpu.r[14].toString(16)}")
 rt.close()
}
