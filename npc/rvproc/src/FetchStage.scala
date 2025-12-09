package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert
// import chisel3.util.experimental.loadMemoryFromFileInline
// import firrtl.annotations.MemoryLoadFileType

import BitMath._

class FetchStage extends Module {
  val io   = IO(new Bundle {
    val out    = Decoupled(new FetchToDecode)
    val fromId = Flipped(Decoupled(new DecodeBackward))
    val fromEx = Flipped(Decoupled(new ExecuteBackward))
    val fromWb = Flipped(Decoupled(new InstCommit))
  })
  val iMem = Module(new PMemBox)

  val idle :: serve :: hold :: start :: Nil = Enum(4)

  val state     = RegInit(start)
  val lastState = RegInit(start)
  lastState := state
  val issueReq = io.fromWb.valid && iMem.io.reqReady
  state := MuxLookup(state, start)(
    Seq(
      idle  -> Mux(issueReq, serve, idle),
      serve -> Mux(iMem.io.respValid, hold, serve),
      hold  -> Mux(io.out.ready, idle, hold),
      start -> serve
    )
  )

  io.out.valid    := state === hold
  io.fromEx.ready := true.B
  io.fromId.ready := true.B
  io.fromWb.ready := state === idle && iMem.io.reqReady
  assert(io.fromWb.valid Implies (state === idle))

  val ResetVector = 0x80000000L.U(ISA.RegBits.W)
  val pc          = RegInit(ResetVector)
  val nextPC      = RegInit(ResetVector)

  iMem.io.clock     := clock
  iMem.io.reset     := reset
  iMem.io.addr      := nextPC          // NOTE:
  iMem.io.wrEn      := false.B
  iMem.io.reqValid  :=
    state === start || (state === idle && issueReq)
  iMem.io.respReady := state === serve // NOTE: 目前的 respReady 总是 true
  iMem.io.byteMask  := DontCare
  iMem.io.wrData    := DontCare

  val brid = io.fromId.bits
  val brex = io.fromEx.bits
  when(io.fromEx.valid || io.fromId.valid) {
    val brTarget = MuxCase(
      pc + 4.U,
      Seq(
        brid.brRel -> (pc + brid.brDel),
        brex.brAbs -> brex.brVal
      )
    )
    nextPC := brTarget

    printf(
      cf"[ ${pc}%x BR ] Rel|AbsJ ${brid.brRel}|${brex.brAbs}"
        + cf" nextPC ${brTarget}%x\n"
    )
  }
  assert(
    brid.brRel Excludes (brex.brAbs),
    cf"Rel|Abs Jmp ${brid.brRel}|${brex.brAbs}"
  )
  assert((io.fromEx.valid || io.fromId.valid) Implies (state === hold))

  when(io.fromWb.valid) {
    pc := nextPC
  }
  assert(io.fromWb.valid Implies (state === idle))

  /** NOTE: To DecodeStage */
  val instLatch = Reg(Tp.InstType())
  when(iMem.io.respValid) {
    instLatch := iMem.io.respData
  }
  val ioid      = io.out.bits
  ioid.pc   := pc
  ioid.inst := instLatch

  when(state === hold) {
    printf(cf"[ ${pc}%x IF ] Holding iMem Resp inst ${ioid.inst}%x\n")
  }.elsewhen(state === serve) {
    printf(
      cf"[ ${pc}%x IF ] Waiting iMem Req addr ${pc}%x reqValid ${iMem.io.reqValid}\n"
    )
  }
  printf(cf"< IF > curr state ${state}\n")
}
