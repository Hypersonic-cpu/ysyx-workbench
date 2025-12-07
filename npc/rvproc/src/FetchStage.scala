package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert
// import chisel3.util.experimental.loadMemoryFromFileInline
// import firrtl.annotations.MemoryLoadFileType

import BitMath._

class FetchStage extends Module {
  val io = IO(new Bundle{
    val out = Decoupled(new FetchToDecode)
    val fromId = Flipped(Decoupled(new DecodeBackward))
    val fromEx = Flipped(Decoupled(new ExecuteBackward))
    val fromWb = Flipped(Decoupled(new InstCommit))
  })
  /**
    * Cycle  1   2   3   1
    * State  F   H   I   F
    * PC     Old Old Old Updated
    */
  val idle :: hold :: fire :: strt :: Nil = Enum(4)
  val state = RegInit(fire)
  state := MuxLookup(state, strt) (Seq(
    hold -> Mux(io.out.ready, idle, hold),
    idle -> Mux(io.fromWb.valid , fire, idle),
    fire -> hold,
    strt -> fire,
    // Delay first memory access for a cycle
  ))

  io.out.valid := state === hold
  // isReady to a comb. stage makes no sense
  io.fromEx.ready := true.B
  io.fromId.ready := true.B
  io.fromWb.ready := state === idle
  assert(io.fromWb.valid Implies(state === idle))

  val pc = RegInit(0x80000000L.U(ISA.RegBits.W))

  /** NOTE: Inst memory port, 
   *  output is passed to decode by comb
   */
  val iMem = Module(new PMemBox)
  iMem.io.clock := clock
  iMem.io.reset := reset
  iMem.io.addr  := pc
  iMem.io.memEn := state === fire
  iMem.io.wrEn  := false.B
  iMem.io.byteMask := 0.U
  iMem.io.data  := 0.U

  /** NOTE: Next PC */
  val brid = io.fromId.bits
  val brex = io.fromEx.bits
  assert(~(brid.brRel && brex.brAbs),
    cf"Rel|Abs jump = ${brid.brRel}|${brex.brAbs}")

  val nextPC = Reg(Tp.RegType()) 
  // Always on cycle 2
  when (io.fromEx.valid || io.fromId.valid) {
    assert(state === hold, "ID/EX Feedback should on cycle2\n")
    val brTarget = MuxCase(pc + 4.U, Seq(
      brid.brRel -> (pc + brid.brDel),
      brex.brAbs -> brex.brVal
    ))
    nextPC := brTarget
    printf(cf"[ ${pc}%x BR ] Rel|AbsJ ${brid.brRel}|${brex.brAbs}"
      + cf" nextPC ${brTarget}%x\n")
  }
  // ALways on cycle 3
  when (io.fromWb.valid) {
    assert(state === idle, "WB Feedback should on cycle3 "
      + "with state === idle\n")
    pc := nextPC
  }

  /** NOTE: To DecodeStage */
  val ioid = io.out.bits
  ioid.pc   := pc
  ioid.inst := iMem.io.loadRaw

  when (state === hold) {
    printf(cf"[ ${pc}%x IF ] iMem Resp inst ${ioid.inst}%x\n")
  }.elsewhen(state === fire) {
    printf(cf"[ ${pc}%x IF ] iMem Req addr ${pc}%x\n")
  }
}
