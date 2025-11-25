package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert

import BitMath._

class LSU extends Module {
  val io = IO(new Bundle {
    val addr   = Input(Tp.AddrType())
    val data   = Input(Tp.RegType())
    val memOp = Input(new MemOp)
    val load   = Output(Tp.RegType())
  })

  val iMem = Module(new PMemBox())
  val lenOp = io.memOp.len
  iMem.io.clock := clock
  iMem.io.reset := reset
  iMem.io.addr  := io.addr
  iMem.io.data  := MuxLookup(lenOp, 0.U) (Seq(
    MemLen.Byte -> (io.data(7, 0) << (io.addr(1,0) << 3.U)),
    MemLen.Half -> (io.data(15,0) << (io.addr(1,1) << 4.U)),
    MemLen.Word -> io.data
  ))
  iMem.io.byteMask := MuxLookup(lenOp, 0.U) (
    Seq(
      MemLen.Byte -> (0x1.U << io.addr(1, 0)),
      MemLen.Half -> (0x3.U << (io.addr(1, 1) << 1.U)),
      MemLen.Word -> 0xf.U
    )
  )
  iMem.io.memEn := lenOp =/= MemLen.None
  // Load and store should not happen together
  iMem.io.wrEn  := io.memOp.isSt

  val lraw = iMem.io.loadRaw >> (io.addr(1, 0) << 3)
  val sext = io.memOp.sExt
  // printf(cf"DPI Chisel Raw ${lraw}%x SEXT ${sext}\n")
  io.load := MuxLookup(lenOp, 0.U) (Seq(
    MemLen.Byte -> Mux(sext, lraw(7, 0).SExt(), lraw(7, 0)),
    MemLen.Half -> Mux(sext, lraw(15, 0).SExt(), lraw(15, 0)),
    MemLen.Word -> lraw
    )
  )
}

class MemoryStage extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new ExecuteToMemory))
    val out = Decoupled(new MemoryToWrBack)
  })
  io.in.ready  := true.B
  io.out.valid := true.B

  val iLsu = Module(new LSU)
  val ioex = io.in.bits
  val iowb = io.out.bits
  iLsu.io.addr := ioex.aluOut
  iLsu.io.data := ioex.rs2Val
  iLsu.io.memOp := ioex.memOp
  iowb.lsuOut  := iLsu.io.load

  // Foward
  iowb.aluOut  := ioex.aluOut
  iowb.takeBr  := ioex.takeBr
  iowb.foward  <> ioex.foward

  printf(cf"[ ${ioex.foawrd.pc} LS ]\n")
}
