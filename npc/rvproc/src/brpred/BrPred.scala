package rvproc.brpred

import chisel3._
import chisel3.util._
import rvproc.{GlbCtrl, ISA, Tp, NoPred, BTFNT, Bimodal}
import rvproc.device.CacheArray

// ─── Configuration ────────────────────────────────────────────────────────────

case class BrPredConf(numEntries: Int = 64) {
  require(
    numEntries > 0 && (numEntries & (numEntries - 1)) == 0,
    s"BrPredConf: numEntries must be power of 2, got $numEntries"
  )
  // PC bits [idxHi:2] are the BTB index (skip byte-offset bits [1:0])
  def idxBits = log2Ceil(numEntries)
  def idxHi   = idxBits + 2 - 1   // inclusive upper bit of index field
  // Remaining upper bits are the tag
  def tagBits = ISA.AddrBits - idxBits - 2
}

// ─── Abstract base class ──────────────────────────────────────────────────────
//
// Query/result pipeline is 1 cycle (SyncReadMem / SRAM).
// io.predTaken and io.targetPC are valid ONE cycle after io.queryPC changes.
//
// Caller (IFU) must supply:
//   queryPC  = Mux(flushWire, brTarget, nextPC)
// so that the result arriving next cycle corresponds to the instruction that
// is being fetched that cycle (pc === RegNext(queryPC)).
abstract class BrPred(val conf: BrPredConf) extends Module {
  val io = IO(new Bundle {
    val queryPC   = Input(Tp.AddrType())
    val predTaken = Output(Bool())
    val targetPC  = Output(Tp.AddrType())
    val btbHit    = Output(Bool())
    val updValid  = Input(Bool())
    val updPC     = Input(Tp.AddrType())
    val updTaken  = Input(Bool())
    val updTarget = Input(Tp.AddrType())
  })

  // Shared helpers
  protected def idxOf(pc: UInt): UInt = pc(conf.idxHi, 2)
  protected def tagOf(pc: UInt): UInt = pc(ISA.AddrBits - 1, conf.idxHi + 1)
}

// ─── BTFNTPredictor ───────────────────────────────────────────────────────────
//
// Backward-Taken / Forward-Not-Taken static predictor.
// Uses a BTB (tag + target) to check hit and direction.
// Predicts taken iff BTB hit AND target < current PC (backward branch).
class BTFNTPredictor(conf: BrPredConf) extends BrPred(conf) {

  // ── BTB arrays (CacheArray selects SyncReadMem or SRAM blackbox) ────────
  val tagArr    = Module(new CacheArray(conf.numEntries, conf.tagBits))
  val targetArr = Module(new CacheArray(conf.numEntries, 32))
  val validArr  = RegInit(VecInit(Seq.fill(conf.numEntries)(false.B)))

  // ── Query pipeline ───────────────────────────────────────────────────────
  val qidx  = idxOf(io.queryPC)
  val qPCR  = RegNext(io.queryPC) // PC for which result is arriving this cycle
  val qidxR = idxOf(qPCR)

  tagArr.io.raddr    := qidx
  tagArr.io.ren      := true.B
  targetArr.io.raddr := qidx
  targetArr.io.ren   := true.B

  // Write-after-read bypass: if we updated the same entry last cycle, use
  // the bypass data (avoids stale read in 1RW-SRAM mode).
  val bypValid  = RegNext(io.updValid && io.updTaken)
  val bypIdx    = RegNext(idxOf(io.updPC))
  val bypTag    = RegNext(tagOf(io.updPC))
  val bypTarget = RegNext(io.updTarget)
  val useByp    = bypValid && bypIdx === qidxR

  val tagData = Mux(useByp, bypTag,    tagArr.io.rdata)
  val tgtData = Mux(useByp, bypTarget, targetArr.io.rdata)

  // Tag check
  val btbHit = validArr(qidxR) && tagData === tagOf(qPCR)

  io.predTaken := btbHit && (tgtData < qPCR)
  io.targetPC  := tgtData
  io.btbHit    := btbHit

  // ── BTB update (taken branches only) ────────────────────────────────────
  val uidx = idxOf(io.updPC)
  tagArr.io.waddr    := uidx
  tagArr.io.wdata    := tagOf(io.updPC)
  tagArr.io.wen      := io.updValid && io.updTaken
  targetArr.io.waddr := uidx
  targetArr.io.wdata := io.updTarget
  targetArr.io.wen   := io.updValid && io.updTaken
  when(io.updValid && io.updTaken) {
    validArr(uidx) := true.B
  }
}

// ─── BimodalPredictor ─────────────────────────────────────────────────────────
//
// Classic 2-bit saturating counter (bimodal) predictor.
// BTB (CacheArray): stores tag + target, same 1-cycle latency.
// BHT (DFF Vec):    2-bit saturating counters, zero-latency combinational read.
// Both are queried in parallel; result valid one cycle after queryPC.
class BimodalPredictor(conf: BrPredConf) extends BrPred(conf) {

  // ── BTB arrays ───────────────────────────────────────────────────────────
  val tagArr    = Module(new CacheArray(conf.numEntries, conf.tagBits))
  val targetArr = Module(new CacheArray(conf.numEntries, 32))
  val validArr  = RegInit(VecInit(Seq.fill(conf.numEntries)(false.B)))

  // ── BHT: 2-bit saturating counters (DFF, combinational read) ────────────
  // Initialised to 2 (weakly taken) — standard bimodal initialisation.
  val bhtArr = RegInit(VecInit(Seq.fill(conf.numEntries)(1.U(2.W))))

  // ── Query pipeline ───────────────────────────────────────────────────────
  val qidx  = idxOf(io.queryPC)
  val qPCR  = RegNext(io.queryPC)
  val qidxR = idxOf(qPCR)

  tagArr.io.raddr    := qidx
  tagArr.io.ren      := true.B
  targetArr.io.raddr := qidx
  targetArr.io.ren   := true.B

  // Write-after-read bypass for BTB arrays
  val bypValid  = RegNext(io.updValid && io.updTaken)
  val bypIdx    = RegNext(idxOf(io.updPC))
  val bypTag    = RegNext(tagOf(io.updPC))
  val bypTarget = RegNext(io.updTarget)
  val useByp    = bypValid && bypIdx === qidxR

  val tagData = Mux(useByp, bypTag,    tagArr.io.rdata)
  val tgtData = Mux(useByp, bypTarget, targetArr.io.rdata)

  // Tag check (parallel with BHT read — both arrive at same cycle)
  val btbHit = validArr(qidxR) && tagData === tagOf(qPCR)
  val bhtCnt = bhtArr(qidxR)

  io.predTaken := btbHit && bhtCnt(1)
  io.targetPC  := tgtData
  io.btbHit    := btbHit

  // ── BTB update (taken branches only — avoids aliasing pollution) ──────────
  val uidx = idxOf(io.updPC)
  tagArr.io.waddr    := uidx
  tagArr.io.wdata    := tagOf(io.updPC)
  tagArr.io.wen      := io.updValid && io.updTaken
  targetArr.io.waddr := uidx
  targetArr.io.wdata := io.updTarget
  targetArr.io.wen   := io.updValid && io.updTaken
  when(io.updValid && io.updTaken) {
    validArr(uidx) := true.B
  }

  // ── BHT update (every branch, taken or not) ───────────────────────────────
  def incrSat(c: UInt): UInt = Mux(c === 3.U, 3.U, c + 1.U)
  def decrSat(c: UInt): UInt = Mux(c === 0.U, 0.U, c - 1.U)
  when(io.updValid) {
    bhtArr(uidx) := Mux(io.updTaken, incrSat(bhtArr(uidx)), decrSat(bhtArr(uidx)))
  }
}

// ─── Factory ─────────────────────────────────────────────────────────────────

object BrPred {
  def apply(): Option[BrPred] = {
    val conf = BrPredConf(GlbCtrl.bpEntries)
    GlbCtrl.bpType match {
      case NoPred  => None
      case BTFNT   => Some(Module(new BTFNTPredictor(conf)))
      case Bimodal => Some(Module(new BimodalPredictor(conf)))
    }
  }
}
