package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert
import rvproc.axi4._
import rvproc.axi4.AXI.RespStatus._
import rvproc.axi4.AXI.BurstOpts._
import rvproc.pmu.FetchPMU
import rvproc.brpred.BrPred
import BitMath._

class FetchStage(resetVector: BigInt, PipeDepth: Int = 3)
    extends Module {
  val io = IO(new Bundle {
    val out          = Decoupled(new FetchToDecode)
    val fromId       = Flipped(Decoupled(Bool()))
    val fromEx       = Flipped(Decoupled(new ExecuteBackward))
    val fromLs       = Input(Bool())
    val iMem         = new AXIBus
    val wbExcp       = Input(Bool())
    val wbExcpTarget = Input(Tp.AddrType())
  })

  val iMem = io.iMem

  val brex       = io.fromEx.bits
  val stBufEmpty = io.fromLs
  val fenceI     = io.fromId.valid && io.fromId.bits
  val fenceState = RegInit(false.B)
  fenceState := Mux(fenceState, !io.fromLs, fenceI)

  val pc     = RegInit(resetVector.U(ISA.RegBits.W))
  val lastPC = RegEnable(io.out.bits.pc, io.out.fire)

  val brTaken  = io.fromEx.valid && brex.brTaken
  val brTarget = MuxCase(
    brex.brLPC + 4.U,
    Seq(
      io.wbExcp -> io.wbExcpTarget,
      brTaken   -> brex.brTarget,
      fenceI    -> (lastPC + 4.U)
    )
  )

  val flushWire =
    (io.fromEx.valid && brex.mispred) || fenceI || io.wbExcp

  val validBuf      = Reg(Vec(PipeDepth + 1, Bool()))
  val pcBuf         = Reg(Vec(PipeDepth + 1, Tp.AddrType()))
  val instBuf       = Reg(Vec(PipeDepth + 1, Tp.InstType()))
  val respBuf       =
    Reg(Vec(PipeDepth + 1, AXI.RespStatus()))
  val predTakenBuf  = Reg(Vec(PipeDepth + 1, Bool()))
  val predTargetBuf = Reg(Vec(PipeDepth + 1, Tp.AddrType()))
  val predBtbHitBuf = Reg(Vec(PipeDepth + 1, Bool()))
  val headPtr       = RegInit(0.U(log2Ceil(PipeDepth + 1).W))
  val tailPtr       = RegInit(0.U(log2Ceil(PipeDepth + 1).W))
  val toidPtr       = RegInit(0.U(log2Ceil(PipeDepth + 1).W))
  def iotaMod(a: UInt) = Mux(a === PipeDepth.U, 0.U, a + 1.U)

  val bufFull   = iotaMod(headPtr) === toidPtr
  val instEmpty = toidPtr === tailPtr

  val btbRdAddr = Wire(Tp.AddrType())
  val bp        = BrPred()

  bp match {
    case Some(p) =>
      p.io.queryPC   := btbRdAddr
      p.io.updValid  := io.fromEx.valid && (brex.isBr || brex.mispred)
      p.io.updPC     := brex.brLPC
      p.io.updTaken  := brex.brTaken
      p.io.updTarget := brex.brTarget
      p.io.updBtbHit := brex.predBtbHit
      p.io.updIsCall := brex.isCall
      p.io.updIsRet  := brex.isRet
    case None    =>
  }

  // BTB result valid when the previous cycle issued a fetch or flush
  // (pc advanced to match btbRdAddr, so btbQueryR == new pc).
  val bpRsltV        =
    RegNext(iMem.ar.fire || flushWire, false.B)
  val bpRawPredTaken = bp.map(_.io.predTaken).getOrElse(false.B)
  val bpTargetPC     = bp.map(_.io.targetPC).getOrElse(0.U)
  val bpRawBtbHit    = bp.map(_.io.btbHit).getOrElse(false.B)

  // Sticky latch: holds prediction when ar.fire is blocked
  val bpPredTakenLatch = RegInit(false.B)
  val bpTargetPCLatch  = RegInit(0.U(ISA.RegBits.W))
  val bpBtbHitLatch    = RegInit(false.B)
  when(flushWire || iMem.ar.fire) {
    bpPredTakenLatch := false.B
    bpBtbHitLatch    := false.B
  }.elsewhen(bpRsltV) {
    bpPredTakenLatch := bpRawPredTaken
    bpTargetPCLatch  := bpTargetPC
    bpBtbHitLatch    := bpRawBtbHit
  }
  val bpPredTaken      =
    (bpRawPredTaken && bpRsltV) || bpPredTakenLatch
  val bpTargetPCEff    =
    Mux(bpRsltV, bpTargetPC, bpTargetPCLatch)
  val bpBtbHitEff      =
    (bpRawBtbHit && bpRsltV) || bpBtbHitLatch

  btbRdAddr := Mux(
    flushWire,
    brTarget,
    Mux(
      bpPredTaken,
      bpTargetPCEff,
      pc + 4.U
    )
  )

  when(iMem.r.fire) {
    instBuf(tailPtr) := iMem.r.bits.data
    respBuf(tailPtr) := iMem.r.bits.resp
    tailPtr          := iotaMod(tailPtr)
  }
  when(iMem.ar.fire) {
    validBuf(headPtr)      := true.B
    pcBuf(headPtr)         := pc
    predTakenBuf(headPtr)  := bpPredTaken
    predTargetBuf(headPtr) := bpTargetPCEff
    predBtbHitBuf(headPtr) := bpBtbHitEff
    headPtr                := iotaMod(headPtr)
  }
  when(io.out.ready && !instEmpty) {
    validBuf(toidPtr) := false.B
    toidPtr           := iotaMod(toidPtr)
  }

  io.out.valid    :=
    !instEmpty && !flushWire && validBuf(toidPtr)
  io.fromEx.ready := true.B
  io.fromId.ready := true.B

  iMem.ar.valid      :=
    !reset.asBool && !bufFull && !fenceState && !flushWire
  iMem.ar.bits.addr  := pc
  iMem.ar.bits.size  := 0x2.U
  iMem.ar.bits.burst := INCR
  iMem.ar.bits.id    := 0.U
  iMem.ar.bits.len   := 0.U
  iMem.r.ready       := true.B
  iMem.aw.valid      := false.B
  iMem.w.valid       := false.B
  iMem.b.ready       := false.B
  iMem.aw.bits       := DontCare
  iMem.w.bits        := DontCare

  assert(~(iMem.b.valid), "Read only port")

  when(flushWire) {
    for (i <- 0 to PipeDepth) {
      validBuf(i) := false.B
    }
    pc := brTarget
  }.otherwise {
    when(iMem.ar.fire) {
      when(bpPredTaken) {
        pc := bpTargetPCEff
      }.otherwise {
        pc := pc + 4.U
      }
    }
  }

  val ioid = io.out.bits
  ioid.pc           := Mux(io.out.valid, pcBuf(toidPtr), 0.U)
  ioid.inst         :=
    Mux(io.out.valid, instBuf(toidPtr), 0.U)
  ioid.predTaken    :=
    Mux(io.out.valid, predTakenBuf(toidPtr), false.B)
  ioid.predTarget   :=
    Mux(io.out.valid, predTargetBuf(toidPtr), 0.U)
  ioid.predBtbHit   :=
    Mux(io.out.valid, predBtbHitBuf(toidPtr), false.B)
  ioid.ifuExcp      :=
    Mux(io.out.valid, respBuf(toidPtr) =/= OKAY, false.B)
  ioid.ifuExcpCause := Mux(
    io.out.valid,
    Mux(respBuf(toidPtr) === SLVERR, 1.U, 12.U),
    0.U
  )

  if (GlbCtrl.debug) {
    val pmu = Module(new FetchPMU)
    pmu.io.clock     := clock
    pmu.io.reset     := reset
    pmu.io.trigFetch := iMem.ar.fire
    pmu.io.trigRecvd := iMem.r.fire
    pmu.io.pcFetch   := pc
    pmu.io.pcRecvd   := pcBuf(tailPtr)
    pmu.io.inst      := io.out.bits.inst
  }
}
