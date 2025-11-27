package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert
// import chisel3.util.experimental.loadMemoryFromFileInline
// import firrtl.annotations.MemoryLoadFileType

class FetchStage extends Module {
  val io = IO(new Bundle{
    val out = Decoupled(new FetchToDecode)
    val fromId = Flipped(Decoupled(new DecodeBackward))
    val fromEx = Flipped(Decoupled(new ExecuteBackward))
  })

  // wait for NEXT stage
  // val idle :: hold :: Nil = Enum(2)
  // val state = RegInit(idle)
  // state := MuxLookup(state, idle) (Seq(
  //   // Transferred and reset
  //   hold -> Mux(io.out.ready, idle, hold),
  //   idle -> Mux(io.in.valid , hold, idle)
  // ))
  // io.out.valid := true.B

  // TODO: Add state
  val pc = RegInit(0x80000000L.U(ISA.RegBits.W))

  /** NOTE: Fetch */
  val iMem = Module(new PMemBox)
  iMem.io.clock := clock
  iMem.io.reset := reset

  iMem.io.addr  := pc
  iMem.io.memEn := true.B
  iMem.io.wrEn  := false.B
  iMem.io.byteMask := 0.U
  iMem.io.data  := 0.U

  /** NOTE: Next PC */
  val brid = io.fromId.bits
  val brex = io.fromEx.bits
  assert(brid.brRel && brex.brAbs, 
    cf"Rel|Abs jump = ${brid.brRel}|${brex.brAbs}")
  val nextPC = MuxCase(pc + 4.U, Seq(
    brid.brRel -> (pc + brid.brDel),
    brex.brAbs -> brex.brVal
  ))
  pc := nextPC
  printf(cf"[ ${pc}%x BR ] Rel|AbsJ ${brid.brRel}|${brex.brAbs}"
    + cf" nextPC ${nextPC}%x\n")

  /** NOTE: To DecodeStage */
  val ioid = io.out.bits
  ioid.pc   := pc
  ioid.inst := iMem.io.loadRaw

  printf(cf"[ ${pc}%x IF ] inst ${ioid.inst}%x\n")
}
