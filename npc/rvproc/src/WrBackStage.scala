package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert
import rvproc.pmu.WrBackPMU

// MUX, Write data selection
class WBU extends Module {
  val io = IO(new Bundle {
    val wbSel = Input(WbSel())
    val pc    = Input(Tp.RegType())
    val csrV  = Input(Tp.RegType())
    val aluV  = Input(Tp.RegType())
    val memV  = Input(Tp.RegType())
    val gprdt = Output(Tp.RegType())
  })

  val snpc = io.pc + 4.U
  io.gprdt := MuxLookup(io.wbSel, 0.U)(
    Seq(
      WbSel.fromAlu -> io.aluV,
      WbSel.fromMem -> io.memV,
      WbSel.fromCsr -> io.csrV,
      WbSel.fromPC  -> snpc
    )
  )
}

class WrBackStage extends Module {
  val io = IO(new Bundle {
    val in     = Flipped(Decoupled(new MemoryToWrBack))
    val toReg  = Decoupled(new RegFromWBU)
    // val toFetch = Decoupled(new InstCommit)
    val fwdDet = Output(new FwBundle)
  })

  io.in.ready    := true.B
  io.toReg.valid := io.in.valid
  // io.toFetch.valid := io.in.valid

  val iWbu = Module(new WBU)
  val iols = io.in.bits
  val iofw = io.in.bits.foward
  iWbu.io.aluV  := iols.aluOut
  iWbu.io.memV  := iols.lsuOut
  iWbu.io.csrV  := iofw.csrVal
  iWbu.io.pc    := iols.aluOut // iofw.pc
  iWbu.io.wbSel := iofw.wbSel

  // when(io.in.valid) {
  //   printf(cf"[ ${iofw.pc}%x WB ] Data = ${iWbu.io.gprdt}%x\n")
  // }

  val ioreg = io.toReg.bits
  ioreg.csrWE := iofw.csrWE
  ioreg.csrIn := iols.aluOut
  ioreg.csrRd := iofw.csrRd
  ioreg.gprWE := iofw.gprWE
  ioreg.gprIn := iWbu.io.gprdt
  ioreg.gprRd := iofw.gprRd

  /** Forward */
  io.fwdDet.valid := io.in.valid
  io.fwdDet.gprFw := true.B
  io.fwdDet.gprDt := ioreg.gprIn

  if (GlbCtrl.debug) {

    /** PMU */
    val pmu = Module(new WrBackPMU)
    pmu.io.clock     := clock
    pmu.io.reset     := reset
    pmu.io.pc        := iofw.pc
    pmu.io.inst      := iofw.inst
    pmu.io.isNewInst := io.in.valid
    pmu.io.stallTp   := iofw.stallT.asUInt.pad(8)
  }
}
