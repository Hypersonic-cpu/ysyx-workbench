package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert

// MUX, Write data selection
class WBU extends Module {
  val io = IO(new Bundle {
    val takeBr = Input(Bool())
    // val jToCsr = Input(Bool())
    val wbSel  = Input(WbSel())
    val pc     = Input(Tp.RegType())
    val csrV   = Input(Tp.RegType())
    val aluV   = Input(Tp.RegType())
    val memV   = Input(Tp.RegType())
    val nxpc   = Output(Tp.RegType())
    val gprdt  = Output(Tp.RegType())
  })

  val snpc = io.pc + 4.U
  val dnpc = io.aluV(31, 1) ## 0.U(1.W) 
  io.nxpc := MuxCase(snpc, Seq(
    io.takeBr -> dnpc,
    // io.jToCsr -> io.csrV
  ))

  printf(cf"[ ${iofw.pc}%x WB ] dnpc ${io.nxpc}%x br${takeBr}\n")

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
    val out = Decoupled(new WrBackToFetch)
    val toReg = Decoupled(new RegFromWBU)
  })
  io.in.ready  := true.B
  io.out.valid := true.B

  io.toReg.valid  := true.B

  // TODO: 将PC跳转提前到EXU之后甚至IDU
  val iWbu = Module(new WBU)
  val iols = io.in.bits
  val ioif = io.out.bits
  val iofw = io.in.bits.foward
  iWbu.io.aluV   := iols.aluOut
  iWbu.io.memV   := iols.lsuOut
  iWbu.io.takeBr := iols.takeBr
  iWbu.io.csrV   := iofw.csrVal
  iWbu.io.pc     := iofw.pc
  iWbu.io.wbSel  := iofw.wbSel

  ioif.npc := iWbu.io.nxpc

  val ioreg = io.toReg.bits
  ioreg.csrWE    := iofw.csrWE
  ioreg.csrIn    := iols.aluOut
  ioreg.csrRd    := iofw.csrRd
  ioreg.gprWE    := iofw.gprWE
  ioreg.gprIn    := iWbu.io.gprdt
  ioreg.gprRd    := iofw.gprRd
}
