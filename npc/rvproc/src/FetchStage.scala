package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert
// import chisel3.util.experimental.loadMemoryFromFileInline
// import firrtl.annotations.MemoryLoadFileType

import BitMath._
import rvproc.axi4._
import rvproc.axi4.AXI.RespStatus._
import rvproc.axi4.AXI.BurstOpts._

class FetchStage extends Module {
  val io   = IO(new Bundle {
    val out    = Decoupled(new FetchToDecode)
    val fromId = Flipped(Decoupled(new DecodeBackward))
    val fromEx = Flipped(Decoupled(new ExecuteBackward))
    val fromWb = Flipped(Decoupled(new InstCommit))
    val iMem   = new AXIBus
  })
  // val iMem = Module(new PMemBox)
  val iMem = io.iMem

  val idle :: serve :: hold :: start :: Nil = Enum(4)

  val state   = RegInit(start)
  val trigIss = (io.fromWb.valid && state === idle) || state === start
  assert((io.fromEx.valid || io.fromId.valid) Implies (state === hold))
  state := MuxLookup(state, start)(
    Seq(
      idle  -> Mux(trigIss && iMem.ar.ready, serve, idle),
      serve -> Mux(iMem.r.valid, hold, serve),
      hold  -> Mux(io.out.ready, idle, hold),
      start -> Mux(trigIss && iMem.ar.ready, serve, start)
    )
  )

  io.out.valid    := state === hold
  io.fromEx.ready := true.B
  io.fromId.ready := true.B
  io.fromWb.ready := state === idle && iMem.ar.ready
  assert(io.fromWb.valid Implies (state === idle), 
    cf"Write back to IFU of state ${state}")

  val ResetVector = 0x80000000L.U(ISA.RegBits.W)
  val pc          = RegInit(ResetVector)
  val nextPC      = RegInit(ResetVector)

  iMem.ar.bits.addr  := nextPC // NOTE:
  iMem.ar.bits.size  := 0b010.U // log2(4)
  iMem.ar.bits.len   := 0.U
  iMem.ar.bits.burst := INCR
  iMem.ar.bits.id    := 0.U // TODO: ID=0
  iMem.ar.valid      := trigIss
  iMem.r.ready       := state === serve
  iMem.aw.valid      := false.B
  iMem.aw.bits.addr  := 0.U
  iMem.aw.bits.size  := 0.U
  iMem.aw.bits.len   := 0.U
  iMem.aw.bits.burst := INCR
  iMem.aw.bits.id    := 0.U
  iMem.w.valid       := false.B
  iMem.w.bits.data   := 0.U
  iMem.w.bits.strb   := 0.U
  iMem.w.bits.last   := false.B
  iMem.b.ready       := false.B
  assert(~(iMem.b.valid), "Read only port")
  assert(iMem.r.valid Implies (iMem.r.bits.resp === OKAY),
    "Inst fetch error")
  assert(
    iMem.r.valid Implies (iMem.r.bits.resp === OKAY),
    cf"Inst Fetch Failed, rresp = ${iMem.r.bits.resp}"
  )

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

  when(io.fromWb.valid) {
    pc := nextPC
  }

  /** NOTE: To DecodeStage */
  val instLatch = Reg(Tp.InstType())
  when(iMem.r.valid) {
    instLatch := iMem.r.bits.data
  }

  val ioid = io.out.bits
  ioid.pc   := pc
  ioid.inst := instLatch

  when(state === hold) {
    printf(cf"[ ${pc}%x IF ] Holding iMem Resp inst ${ioid.inst}%x\n")
  }.elsewhen(state === serve) {
    printf(
      cf"[ ${pc}%x IF ] Waiting iMem Req of addr ${pc}%x\n")
  }
  printf(cf"< IF > curr state ${state}\n")
}
