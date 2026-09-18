import vxpcore.*
import java.io.File
fun main(){
  val mem=GuestMemory(); mem.map(0x1000,0x2000,true,true,true)
  val rt=MreRuntime(mem,File("/mnt/data/VXP-Core-Library-v0.8.2/build/th-hw-fs"))
  val cpu=ArmCpu(mem,rt,false)
  // STRH r3,[r2,#2] = 0x8053; LDRH r1,[r2,#2] = 0x8851
  mem.write16(0x1000,0x8053,force=true); mem.write16(0x1002,0x8851,force=true)
  cpu.reset(0x1001); cpu.r[2]=0x1800; cpu.r[3]=0xABCD
  cpu.step(); check(mem.read16(0x1802)==0xABCD)
  cpu.step(); check(cpu.r[1]==0xABCD)
  println("[OK] Thumb immediate STRH/LDRH")
}
