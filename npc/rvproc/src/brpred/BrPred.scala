package rvproc.brpred

import chisel3._
import chisel3.util._
import rvproc.{BTFNT, Bimodal, GlbCtrl, ISA, NoPred, Tp}
import rvproc.device.CacheArray

case class BrPredConf(numEntries: Int = 64) {
  require(
    numEntries > 0 && (numEntries & (numEntries - 1)) == 0,
    s"BrPredConf: numEntries must be power of 2, got $numEntries"
  )
  def idxBits = log2Ceil(numEntries)
  def idxHi   = idxBits + 2 - 1
  def tagBits = ISA.AddrBits - idxBits - 2
}

// predTaken/targetPC valid ONE cycle after queryPC changes.
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
    val updBtbHit = Input(Bool())
  })

  protected def idxOf(pc: UInt): UInt = pc(conf.idxHi, 2)
  protected def tagOf(pc: UInt): UInt =
    pc(ISA.AddrBits - 1, conf.idxHi + 1)
}

// Predict taken iff BTB hit AND target < PC (backward branch).
class BTFNTPredictor(conf: BrPredConf) extends BrPred(conf) {

  val tagArr    = Module(
    new CacheArray(conf.numEntries, conf.tagBits)
  )
  val targetArr = Module(
    new CacheArray(conf.numEntries, 32)
  )
  val validArr  =
    RegInit(VecInit(Seq.fill(conf.numEntries)(false.B)))

  val qidx  = idxOf(io.queryPC)
  val qPCR  = RegNext(io.queryPC)
  val qidxR = idxOf(qPCR)

  tagArr.io.raddr    := qidx
  tagArr.io.ren      := true.B
  targetArr.io.raddr := qidx
  targetArr.io.ren   := true.B

  val bypValid  = RegNext(io.updValid && io.updTaken)
  val bypIdx    = RegNext(idxOf(io.updPC))
  val bypTag    = RegNext(tagOf(io.updPC))
  val bypTarget = RegNext(io.updTarget)
  val useByp    = bypValid && bypIdx === qidxR

  val tagData = Mux(useByp, bypTag, tagArr.io.rdata)
  val tgtData =
    Mux(useByp, bypTarget, targetArr.io.rdata)

  val btbHit =
    validArr(qidxR) && tagData === tagOf(qPCR)

  io.predTaken := btbHit && (tgtData < qPCR)
  io.targetPC  := tgtData
  io.btbHit    := btbHit

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

// 2-bit saturating counter predictor with CacheArray BTB.
// BHT updates only when BTB hit or branch taken
// (prevents aliased not-taken branches from polluting
// counters owned by other PCs).
class BimodalPredictor(conf: BrPredConf) extends BrPred(conf) {

  val tagArr    = Module(
    new CacheArray(conf.numEntries, conf.tagBits)
  )
  val targetArr = Module(
    new CacheArray(conf.numEntries, 32)
  )
  val validArr  =
    RegInit(VecInit(Seq.fill(conf.numEntries)(false.B)))

  val bhtArr = RegInit(
    VecInit(Seq.fill(conf.numEntries)(1.U(2.W)))
  )

  val qidx  = idxOf(io.queryPC)
  val qPCR  = RegNext(io.queryPC)
  val qidxR = idxOf(qPCR)

  tagArr.io.raddr    := qidx
  tagArr.io.ren      := true.B
  targetArr.io.raddr := qidx
  targetArr.io.ren   := true.B

  val bypValid  = RegNext(io.updValid && io.updTaken)
  val bypIdx    = RegNext(idxOf(io.updPC))
  val bypTag    = RegNext(tagOf(io.updPC))
  val bypTarget = RegNext(io.updTarget)
  val useByp    = bypValid && bypIdx === qidxR

  val tagData = Mux(useByp, bypTag, tagArr.io.rdata)
  val tgtData =
    Mux(useByp, bypTarget, targetArr.io.rdata)

  val btbHit =
    validArr(qidxR) && tagData === tagOf(qPCR)
  val bhtCnt = bhtArr(qidxR)

  io.predTaken := btbHit && bhtCnt(1)
  io.targetPC  := tgtData
  io.btbHit    := btbHit

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

  def incrSat(c: UInt): UInt =
    Mux(c === 3.U, 3.U, c + 1.U)
  def decrSat(c: UInt): UInt =
    Mux(c === 0.U, 0.U, c - 1.U)
  when(io.updValid && (io.updBtbHit || io.updTaken)) {
    bhtArr(uidx) := Mux(
      io.updTaken && !io.updBtbHit,
      2.U,
      Mux(
        io.updTaken,
        incrSat(bhtArr(uidx)),
        decrSat(bhtArr(uidx))
      )
    )
  }
}

object BrPred {
  def apply(): Option[BrPred] = {
    val conf = BrPredConf(GlbCtrl.bpEntries)
    GlbCtrl.bpType match {
      case NoPred  => None
      case BTFNT   =>
        Some(Module(new BTFNTPredictor(conf)))
      case Bimodal =>
        Some(Module(new BimodalPredictor(conf)))
    }
  }
}
