package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert
import rvproc.axi4._
import rvproc.axi4.AXI.RespStatus._
import rvproc.axi4.AXI.BurstOpts._
import rvproc.pmu.FetchPMU
import BitMath._

class FetchStage(resetVector: BigInt) extends Module {
  val io = IO(new Bundle {
    val out    = Decoupled(new FetchToDecode)
    val fromEx = Flipped(Decoupled(new ExecuteBackward))
    val fromWb = Flipped(Decoupled(new InstCommit))
    val iMem   = new AXIBus
  })

  val idle :: serve :: Nil = Enum(2)

  val iMem  = io.iMem
  val state = RegInit(idle)

  val trigFetch = (!reset.asBool) && state === idle
  val nextState = MuxLookup(state, idle)(
    Seq(
      idle  -> Mux(trigFetch && iMem.ar.ready, serve, idle),
      serve -> Mux(iMem.r.valid, idle, serve)
    )
  )
  state := nextState

  val pipeShift = io.out.ready && nextState =/= serve
  val brex      = io.fromEx.bits
  val brPending = RegInit(false.B)
  val flushWire = io.fromEx.valid && brex.take
  val instValid = iMem.r.valid
  val flushThis = brPending || flushWire
  brPending := (brPending || flushWire) && !instValid

  io.out.valid    := instValid && !flushThis
  io.fromEx.ready := true.B
  io.fromWb.ready := true.B

  val pc     = RegInit(resetVector.U(ISA.RegBits.W))
  val nextPC = RegInit((resetVector+4).U(ISA.RegBits.W))
  val pastPCs = Reg(Vec(5, Tp.AddrType()))

  val fetchPipeLen = 1
  val flyPCs = Reg(Vec(fetchPipeLen, Tp.AddrType()))
  val flyPCv = Reg(Vec(fetchPipeLen, Bool()))

  when (pipeShift) {
    for (i <- 1 until 5) {
      pastPCs(i) := pastPCs(i-1)
    }
    pastPCs(0) := pc

    // for (i <- 1 until fetchPipeLen) {
    //   flyPCs(i) := flyPCs(i+1)
    //   flyPCv(i) := flyPCv(i+1)
    // }
    // flyPCs(0) := pc
  }

  // TODO: iCache改成流水
  iMem.ar.bits.addr  := pc
  iMem.ar.bits.size  := 0x2.U        // log2(4)
  iMem.ar.bits.len   := 0.U
  iMem.ar.bits.burst := INCR
  iMem.ar.bits.id    := 0.U          // TODO: ID=0
  iMem.ar.valid      := trigFetch
  // NOTE: 让iCache等到直到IDU以后清空
  iMem.r.ready       := io.out.ready // state === serve
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
  assert(
    iMem.r.valid Implies (iMem.r.bits.resp === OKAY),
    "Inst fetch error"
  )
  assert(
    iMem.r.valid Implies (iMem.r.bits.resp === OKAY),
    cf"Inst Fetch Failed, rresp = ${iMem.r.bits.resp}"
  )

  when(flushWire) {
    val brTarget = MuxCase(
      nextPC,
      Seq(
        brex.brAbs -> brex.brVal,
        brex.brRel -> (pastPCs(0) + brex.brDel)
      )
    )
    when(pipeShift) {
      pc := brTarget
      nextPC := brTarget + 4.U
    }.otherwise{
      nextPC := brTarget
    }
  }.otherwise {
    when(pipeShift) {
      pc     := nextPC
      nextPC := nextPC + 4.U
    }
  }

  val ioid = io.out.bits
  ioid.pc   := pc
  ioid.inst := iMem.r.bits.data

  val pmu = Module(new FetchPMU)
  val delayedPipe = RegNext(pipeShift)
  pmu.io.clock     := clock
  pmu.io.reset     := reset
  pmu.io.trigFetch := delayedPipe
  pmu.io.trigIssue := io.out.fire
  pmu.io.pc        := pc
  pmu.io.inst      := io.out.bits.inst
}
