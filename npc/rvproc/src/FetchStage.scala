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
    val out    = Decoupled(new FetchToDecode)
    val fromId = Flipped(Decoupled(Bool())) // fence.i
    val fromEx = Flipped(Decoupled(new ExecuteBackward))
    val fromLs = Input(Bool())              // store buffer empty
    val iMem   = new AXIBus
  })

  val iMem = io.iMem

  val brex       = io.fromEx.bits
  val stBufEmpty = io.fromLs
  val fenceI     = io.fromId.valid && io.fromId.bits
  val fenceState = RegInit(false.B)
  fenceState := Mux(fenceState, !io.fromLs, fenceI)

  val pc     = RegInit(resetVector.U(ISA.RegBits.W))
  val nextPC = RegInit((resetVector + 4).U(ISA.RegBits.W))
  val lastPC = RegEnable(io.out.bits.pc, io.out.fire)

  // ── Flush target computation ─────────────────────────────────────────────
  // Priority: brAbs > brRel > fenceI > mispred-not-taken (default)
  val brAbs = io.fromEx.valid && brex.brAbs
  val brRel = io.fromEx.valid && brex.brRel
  val brTarget = MuxCase(
    brex.brLPC + 4.U, // mispred not-taken: resume from PC after branch
    Seq(
      brAbs  -> brex.brVal,                 // JALR / JAL / ecall
      brRel  -> (brex.brLPC + brex.brDel),  // taken B-type
      fenceI -> (lastPC + 4.U)              // fence.i: resume after it
    )
  )

  // ── Flush condition: only on MISPREDICTION or fence.i ────────────────────
  val flushWire =
    (io.fromEx.valid && brex.mispred) || fenceI

  // ── Fetch buffer ─────────────────────────────────────────────────────────
  val validBuf      = Reg(Vec(PipeDepth + 1, Bool()))
  val pcBuf         = Reg(Vec(PipeDepth + 1, Tp.AddrType()))
  val instBuf       = Reg(Vec(PipeDepth + 1, Tp.InstType()))
  val predTakenBuf  = Reg(Vec(PipeDepth + 1, Bool()))
  val predTargetBuf = Reg(Vec(PipeDepth + 1, Tp.AddrType()))
  val predBtbHitBuf = Reg(Vec(PipeDepth + 1, Bool()))
  val headPtr       = RegInit(0.U(log2Ceil(PipeDepth + 1).W))
  val tailPtr       = RegInit(0.U(log2Ceil(PipeDepth + 1).W))
  val toidPtr       = RegInit(0.U(log2Ceil(PipeDepth + 1).W))
  def iotaMod(a: UInt) = Mux(a === PipeDepth.U, 0.U, a + 1.U)

  val bufFull   = iotaMod(headPtr) === toidPtr
  val instEmpty = toidPtr === tailPtr

  // ── Branch predictor instantiation ───────────────────────────────────────
  // btbRdAddr is forward-declared so bp.io.queryPC can reference it before
  // the Mux assignment (which depends on bpPredTaken defined below).
  val btbRdAddr = Wire(Tp.AddrType())
  val bp = BrPred()

  bp match {
    case Some(p) =>
      p.io.queryPC   := btbRdAddr
      p.io.updValid  := io.fromEx.valid && brex.isBr
      p.io.updPC     := brex.brLPC
      p.io.updTaken  := brex.take   // actual outcome
      p.io.updTarget := Mux(brex.brAbs, brex.brVal, brex.brLPC + brex.brDel)
    case None => // no predictor wired
  }

  // ── BP result validity guard ──────────────────────────────────────────────
  // BTB result is valid precisely when pc == RegNext(btbRdAddr).
  val btbQueryR      = RegNext(btbRdAddr)
  val bpRsltV        = (pc === btbQueryR)
  val bpRawPredTaken = bp.map(_.io.predTaken).getOrElse(false.B)
  val bpTargetPC     = bp.map(_.io.targetPC).getOrElse(0.U)
  val bpRawBtbHit    = bp.map(_.io.btbHit).getOrElse(false.B)

  // Sticky latch: holds the prediction from when bpRsltV first became true
  // until ar.fire consumes it.  Needed when the fetch buffer is full
  // (ar.fire=false) during the single cycle that bpRsltV is true.
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
  val bpPredTaken   = (bpRawPredTaken && bpRsltV) || bpPredTakenLatch
  val bpTargetPCEff = Mux(bpRsltV, bpTargetPC, bpTargetPCLatch)
  val bpBtbHitEff   = (bpRawBtbHit && bpRsltV) || bpBtbHitLatch

  // BTB query address:
  //  - flush        : redirect target (result ready for the post-flush fetch)
  //  - ar.fire+pred : predicted target (result ready for the target's fetch)
  //  - otherwise    : nextPC (one cycle ahead, enabling zero-stall prediction)
  btbRdAddr := Mux(
    flushWire, brTarget,
    Mux(bpPredTaken && iMem.ar.fire, bpTargetPCEff, nextPC)
  )

  // ── Recv inst from iCache ─────────────────────────────────────────────────
  when(iMem.r.fire) {
    instBuf(tailPtr) := iMem.r.bits.data
    tailPtr          := iotaMod(tailPtr)
  }
  // ── Send fetch to iCache + record prediction for this fetch ───────────────
  when(iMem.ar.fire) {
    validBuf(headPtr)      := true.B
    pcBuf(headPtr)         := pc
    predTakenBuf(headPtr)  := bpPredTaken
    predTargetBuf(headPtr) := bpTargetPCEff
    predBtbHitBuf(headPtr) := bpBtbHitEff
    headPtr                := iotaMod(headPtr)
  }
  // ── Issue to IDU ──────────────────────────────────────────────────────────
  when(io.out.ready && !instEmpty) {
    validBuf(toidPtr) := false.B
    toidPtr           := iotaMod(toidPtr)
  }

  io.out.valid    := !instEmpty && !flushWire && validBuf(toidPtr)
  io.fromEx.ready := true.B
  io.fromId.ready := true.B

  iMem.ar.valid      := !reset.asBool && !bufFull && !fenceState && !flushWire
  iMem.ar.bits.addr  := pc
  iMem.ar.bits.size  := 0x2.U  // log2(4)
  iMem.ar.bits.burst := INCR
  iMem.ar.bits.id    := 0.U    // iCache
  iMem.ar.bits.len   := 0.U
  iMem.r.ready       := true.B
  iMem.aw.valid      := false.B
  iMem.w.valid       := false.B
  iMem.b.ready       := false.B
  iMem.aw.bits       := DontCare
  iMem.w.bits        := DontCare

  assert(~(iMem.b.valid), "Read only port")

  // ── PC sequencing ─────────────────────────────────────────────────────────
  when(flushWire) {
    for (i <- 0 to PipeDepth) {
      validBuf(i) := false.B
    }
    pc     := brTarget
    nextPC := brTarget + 4.U
  }.otherwise {
    when(iMem.ar.fire) {
      // When BP predicts taken, jump directly to the predicted target.
      // This prevents fetching the wrong-path sequential fallthrough (PC+4),
      // which would otherwise execute without any flush (correct prediction
      // never triggers brDet) and corrupt architectural register state.
      when(bpPredTaken) {
        pc     := bpTargetPCEff
        nextPC := bpTargetPCEff + 4.U
      }.otherwise {
        pc     := nextPC
        nextPC := nextPC + 4.U
      }
    }
  }

  // ── Output to IDU ─────────────────────────────────────────────────────────
  val ioid = io.out.bits
  ioid.pc         := Mux(io.out.valid, pcBuf(toidPtr), 0.U)
  ioid.inst       := Mux(io.out.valid, instBuf(toidPtr), 0.U)
  ioid.predTaken  := Mux(io.out.valid, predTakenBuf(toidPtr), false.B)
  ioid.predTarget := Mux(io.out.valid, predTargetBuf(toidPtr), 0.U)
  ioid.predBtbHit := Mux(io.out.valid, predBtbHitBuf(toidPtr), false.B)

  if (GlbCtrl.debug) {
    when(bpPredTaken && iMem.ar.fire) {
      printf(cf"[BP] predTaken pc=0x${pc}%x target=0x${bpTargetPCEff}%x bpRsltV=${bpRsltV}\n")
    }
    when(flushWire) {
      printf(
        cf"[FLUSH] mispred=${brex.mispred} fenceI=${fenceI} brTarget=0x${brTarget}%x "
      )
      printf(cf"brAbs=${brex.brAbs} brRel=${brex.brRel} brLPC=0x${brex.brLPC}%x brDel=0x${brex.brDel}%x\n")
    }
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

