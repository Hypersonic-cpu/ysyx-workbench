package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert

// MUX, Write data selection
class WBU extends Module {
  val io = IO(new Bundle {
    // val takeBr = Input(Bool())
    val wbSel  = Input(WbSel())
    // val brSel  = Input(BrSel())
    val pc     = Input(Tp.RegType())
    val csrV   = Input(Tp.RegType())
    val aluV   = Input(Tp.RegType())
    val memV   = Input(Tp.RegType())
    // val nxpc   = Output(Tp.RegType())
    val gprdt  = Output(Tp.RegType())
  })

  val snpc = io.pc + 4.U
  // val aluc = io.aluV(31, 1) ## 0.U(1.W)
  // val dnpc = MuxLookup(io.brSel, aluc) (Seq(
  //   BrSel.fromAlu -> aluc,
  //   BrSel.fromCsr -> io.csrV
  // ))
  // io.nxpc := Mux(io.takeBr, dnpc, snpc)

  printf(cf"[ ${io.pc}%x WB ] Data = ${io.gprdt}%x\n")

    // Mux(jmp, dnpc, snpc)
  io.gprdt := MuxLookup(io.wbSel, 0.U) (Seq(
    WbSel.fromAlu -> io.aluV, 
    WbSel.fromMem -> io.memV,
    WbSel.fromCsr -> io.csrV,
    WbSel.fromPC  -> snpc
  ))
}

class WrBackStage extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new MemoryToWrBack))
    val toReg = Decoupled(new RegFromWBU)
  })
  io.in.ready  := true.B

  io.toReg.valid  := true.B

  val iWbu = Module(new WBU)
  val iols = io.in.bits
  val iofw = io.in.bits.foward
  iWbu.io.aluV   := iols.aluOut
  iWbu.io.memV   := iols.lsuOut
  iWbu.io.csrV   := iofw.csrVal
  iWbu.io.pc     := iofw.pc
  iWbu.io.wbSel  := iofw.wbSel
  iWbu.io.brSel  := iofw.brSel

  val ioreg = io.toReg.bits
  ioreg.csrWE    := iofw.csrWE
  ioreg.csrIn    := iols.aluOut
  ioreg.csrRd    := iofw.csrRd
  ioreg.gprWE    := iofw.gprWE
  ioreg.gprIn    := iWbu.io.gprdt
  ioreg.gprRd    := iofw.gprRd
}
