package sCPU

import chisel3._
import chisel3.util._
import chisel3.experimental._
import chisel3.util.experimental.loadMemoryFromFileInline

class sCPUFpga extends Module {
  val io = IO(new Bundle {
    val probeEna = Input(Bool())
    val probePin = Input(UInt(sISA.RegIdx.W))
    val segDisp  = Output(Vec(8, UInt(8.W)))
  })
  val core = Module(new sCPU.sCPU("/mnt/hgfs/Arch-PA/ysyx-workbench/npc/scpu/prog-rom/Add1To10.sCPU.bin"))
  core.io.regProbe := io.probePin

  val rend   = for { i <- 0 until 8 } yield Module(new HexTo7Seg())
  for (i <- Seq(3, 6)) {
    rend(i).io.ena := false.B
    rend(i).io.in := 0.U
  }

  val dispReg = RegInit(0.U(8.W))
  dispReg := Mux(core.io.dispEna, core.io.dispVal, dispReg)
  rend(4).io.ena := true.B // core.io.dispEna
  rend(5).io.ena := true.B // core.io.dispEna
  rend(4).io.in  := dispReg(3, 0) // core.io.dispVal(3, 0)
  rend(5).io.in  := dispReg(7, 4) // core.io.dispVal(7, 4)

  rend(7).io.ena := true.B
  rend(7).io.in  := core.io.outPC

  rend(0).io.ena := io.probeEna
  rend(1).io.ena := io.probeEna
  rend(2).io.ena := io.probeEna
  rend(0).io.in  := core.io.outProbe(3, 0)
  rend(1).io.ena := core.io.outProbe(7, 4)
  rend(2).io.in  := io.probePin

  for (i <- 0 until 8) {
    io.segDisp(i) := ~rend(i).io.segMsbA
  }
}
