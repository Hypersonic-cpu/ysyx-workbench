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

  val flushFromEx = io.fromEx.valid && brex.mispred

  val brTaken  = io.fromEx.valid && brex.brTaken
  val brTarget = MuxCase(
    brex.brLPC4,
    Seq(
      io.wbExcp -> io.wbExcpTarget,
      brTaken   -> brex.brTarget,
      fenceI    -> (lastPC + 4.U)
    )
  )

  val flushWire = flushFromEx || fenceI || io.wbExcp

  val validBuf      = Reg(Vec(PipeDepth + 1, Bool()))
  val pcBuf         = Reg(Vec(PipeDepth + 1, Tp.AddrType()))
  val snpcBuf       = Reg(Vec(PipeDepth + 1, Tp.AddrType()))
  val instBuf       = Reg(Vec(PipeDepth + 1, Tp.InstType()))
  val respBuf       =
    Reg(Vec(PipeDepth + 1, AXI.RespStatus()))
  val predTakenBuf  = Reg(Vec(PipeDepth + 1, Bool()))
  val predTargetBuf = Reg(Vec(PipeDepth + 1, Tp.AddrType()))
  val predBtbHitBuf = Reg(Vec(PipeDepth + 1, Bool()))
  val predBhtCntBuf = Reg(Vec(PipeDepth + 1, UInt(2.W)))
  val headPtr       = RegInit(0.U(log2Ceil(PipeDepth + 1).W))
  val tailPtr       = RegInit(0.U(log2Ceil(PipeDepth + 1).W))
  val toidPtr       = RegInit(0.U(log2Ceil(PipeDepth + 1).W))
  val discardCnt    = RegInit(0.U(log2Ceil(PipeDepth + 1).W))
  def iotaMod(a: UInt) = Mux(a === PipeDepth.U, 0.U, a + 1.U)

  val bufFull   = iotaMod(headPtr) === toidPtr
  val instEmpty = toidPtr === tailPtr

  val btbRdAddr = Wire(Tp.AddrType())
  val bp        = BrPred()

  bp match {
    case Some(p) =>
      p.io.queryPC     := btbRdAddr
      p.io.updValid    :=
        io.fromEx.valid && (brex.isBr || brex.predBtbHit)
      p.io.updPC       := brex.brLPC
      p.io.updTaken    := brex.brTaken
      p.io.updTarget   := brex.brTarget
      p.io.updBtbHit   := brex.predBtbHit
      p.io.updOldCnt   := brex.predBhtCnt
      p.io.updIsCall   := brex.isCall
      p.io.updIsRet    := brex.isRet
      p.io.updIsBranch := brex.isBr
    case None    =>
  }

  // BPU raw outputs (valid 1 cycle after queryPC change)
  val bpRawPredTaken = bp.map(_.io.predTaken).getOrElse(false.B)
  val bpRawTargetPC  = bp.map(_.io.targetPC).getOrElse(0.U)
  val bpRawBtbHit    = bp.map(_.io.btbHit).getOrElse(false.B)
  val bpRawBhtCnt    = bp.map(_.io.bhtCnt).getOrElse(0.U)

  // Query BPU with the PC of the just-received instruction
  btbRdAddr := pcBuf(tailPtr)

  val earlyRedirect = Wire(Bool())

  val validRecv = iMem.r.fire && discardCnt === 0.U &&
    !earlyRedirect && !flushWire

  // Pre-decode at receipt: B=0x63, JAL=0x6F, JALR=0x67
  val tailIsBranch = {
    val op = iMem.r.bits.data(6, 0)
    op === "b1100011".U || op === "b1101111".U ||
    op === "b1100111".U
  }

  // 1 cycle after receipt: BPU outputs aligned with bpRsltV
  val bpRsltV = RegInit(false.B)
  bpRsltV := validRecv
  when(flushWire || earlyRedirect) { bpRsltV := false.B }

  val bpQueryBranch = RegNext(tailIsBranch, false.B)
  val bpTailPtr     = RegNext(tailPtr)

  earlyRedirect := bpRsltV && bpQueryBranch &&
    bpRawPredTaken && !flushWire

  val metaHold = bpRsltV && (toidPtr === bpTailPtr)

  when(bpRsltV && !flushWire && !earlyRedirect) {
    predBtbHitBuf(bpTailPtr) := bpRawBtbHit
    predBhtCntBuf(bpTailPtr) := bpRawBhtCnt
  }
  when(earlyRedirect) {
    predTakenBuf(bpTailPtr)  := true.B
    predTargetBuf(bpTailPtr) := bpRawTargetPC
    predBtbHitBuf(bpTailPtr) := bpRawBtbHit
    predBhtCntBuf(bpTailPtr) := bpRawBhtCnt
  }

  when(iMem.r.fire) {
    when(discardCnt > 0.U || earlyRedirect) {
      when(!earlyRedirect) {
        discardCnt := discardCnt - 1.U
      }
    }.otherwise {
      instBuf(tailPtr) := iMem.r.bits.data
      respBuf(tailPtr) := iMem.r.bits.resp
      tailPtr          := iotaMod(tailPtr)
    }
  }
  when(iMem.ar.fire) {
    validBuf(headPtr)      := true.B
    pcBuf(headPtr)         := pc
    snpcBuf(headPtr)       := pc + 4.U
    predTakenBuf(headPtr)  := false.B
    predTargetBuf(headPtr) := 0.U
    predBtbHitBuf(headPtr) := false.B
    predBhtCntBuf(headPtr) := 0.U
    headPtr                := iotaMod(headPtr)
  }
  when(io.out.fire) {
    validBuf(toidPtr) := false.B
    toidPtr           := iotaMod(toidPtr)
  }

  io.out.valid    :=
    !instEmpty && !flushWire &&
    !metaHold && validBuf(toidPtr)
  io.fromEx.ready := true.B
  io.fromId.ready := true.B

  iMem.ar.valid      :=
    !reset.asBool && !bufFull && !fenceState &&
    !flushWire
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
    // Count in-flight requests that will arrive after flush.
    // Subtract r.fire if a response arrives this cycle.
    val inFlight = headPtr - tailPtr
    discardCnt := inFlight + discardCnt -
      (iMem.r.fire).asUInt
    headPtr := 0.U
    tailPtr := 0.U
    toidPtr := 0.U
    for (i <- 0 to PipeDepth) {
      validBuf(i) := false.B
    }
    pc := brTarget
  }.elsewhen(earlyRedirect) {
    val newTailPtr = iotaMod(bpTailPtr)
    tailPtr := newTailPtr
    headPtr := newTailPtr
    val inFlight = headPtr - newTailPtr
    discardCnt := inFlight - iMem.r.fire.asUInt +
      iMem.ar.fire.asUInt
    pc         := bpRawTargetPC
  }.otherwise {
    when(iMem.ar.fire) {
      pc := pc + 4.U
    }
  }
  val prevPC   = RegNext(pc)
  val prevFire = RegNext(iMem.ar.fire)

  val ioid = io.out.bits

  // Pre-decode at output for safety: mask predTaken on non-branch
  val isBranchPD     = {
    val op = instBuf(toidPtr)(6, 0)
    op === "b1100011".U || op === "b1101111".U ||
    op === "b1100111".U
  }

  ioid.pc           := pcBuf(toidPtr)
  ioid.inst         := instBuf(toidPtr)
  ioid.predTaken    :=
    predTakenBuf(toidPtr) && isBranchPD
  ioid.predTarget   := predTargetBuf(toidPtr)
  ioid.predBtbHit   := predBtbHitBuf(toidPtr)
  ioid.predBhtCnt   := predBhtCntBuf(toidPtr)
  ioid.ifuExcp      := respBuf(toidPtr) =/= OKAY
  ioid.ifuExcpCause :=
    Mux(respBuf(toidPtr) === SLVERR, 1.U, 12.U)

  if (GlbCtrl.debug) {
    val pmu = Module(new FetchPMU)
    pmu.io.clock     := clock
    pmu.io.reset     := reset
    pmu.io.trigFetch := iMem.ar.fire
    pmu.io.trigRecvd := validRecv
    pmu.io.pcFetch   := iMem.ar.bits.addr
    pmu.io.pcRecvd   := pcBuf(tailPtr)
    pmu.io.inst      := io.out.bits.inst
  }
}
