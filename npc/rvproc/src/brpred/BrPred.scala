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
    val updIsCall = Input(Bool())
    val updIsRet  = Input(Bool())
  })

  protected def idxOf(pc: UInt): UInt = pc(conf.idxHi, 2)
  protected def tagOf(pc: UInt): UInt =
    pc(ISA.AddrBits - 1, conf.idxHi + 1)
}

class ReturnAddrStack(depth: Int) extends Module {
  val io = IO(new Bundle {
    val push     = Input(Bool())
    val pushAddr = Input(Tp.AddrType())
    val pop      = Input(Bool())
    val top      = Output(Tp.AddrType())
    val topValid = Output(Bool())
  })

  val stack = Reg(Vec(depth, Tp.AddrType()))
  val tos   = RegInit(0.U(log2Ceil(depth).W))
  val cnt   = RegInit(0.U(log2Ceil(depth + 1).W))

  io.top      := stack(tos)
  io.topValid := cnt > 0.U

  when(io.push && io.pop) {
    stack(tos) := io.pushAddr
  }.elsewhen(io.push) {
    val nxt = Mux(
      tos === (depth - 1).U,
      0.U,
      tos + 1.U
    )
    stack(nxt) := io.pushAddr
    tos        := nxt
    when(cnt < depth.U) { cnt := cnt + 1.U }
  }.elsewhen(io.pop) {
    when(cnt > 0.U) {
      tos := Mux(tos === 0.U, (depth - 1).U, tos - 1.U)
      cnt := cnt - 1.U
    }
  }
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
  val typeArr   =
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
  val bypIsRet  = RegNext(io.updIsRet)
  val useByp    = bypValid && bypIdx === qidxR

  val tagData = Mux(useByp, bypTag, tagArr.io.rdata)
  val tgtData =
    Mux(useByp, bypTarget, targetArr.io.rdata)

  val btbHitRaw =
    validArr(qidxR) && tagData === tagOf(qPCR)
  val btbHit    = if (GlbCtrl.useSram) {
    btbHitRaw && !(bypValid && !useByp)
  } else btbHitRaw
  val isRetBit  = Mux(useByp, bypIsRet, typeArr(qidxR))

  val hasRas   = GlbCtrl.rasSize > 0
  val rasValid = WireDefault(false.B)
  val rasTop   = WireDefault(0.U(ISA.AddrBits.W))
  if (hasRas) {
    val ras = Module(new ReturnAddrStack(GlbCtrl.rasSize))
    ras.io.push     := io.updValid && io.updIsCall
    ras.io.pushAddr := io.updPC + 4.U
    ras.io.pop      := io.updValid && io.updIsRet
    rasValid        := ras.io.topValid
    rasTop          := ras.io.top
  }

  val useRas = btbHit && isRetBit && rasValid
  io.predTaken := btbHit && (useRas || (tgtData < qPCR))
  io.targetPC  := Mux(useRas, rasTop, tgtData)
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
    typeArr(uidx)  := io.updIsRet
  }
}

class BimodalPredictor(conf: BrPredConf) extends BrPred(conf) {

  val tagArr    = Module(
    new CacheArray(conf.numEntries, conf.tagBits)
  )
  val targetArr = Module(
    new CacheArray(conf.numEntries, 32)
  )
  val validArr  =
    RegInit(VecInit(Seq.fill(conf.numEntries)(false.B)))
  val typeArr   =
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
  val bypIsRet  = RegNext(io.updIsRet)
  val useByp    = bypValid && bypIdx === qidxR

  val tagData = Mux(useByp, bypTag, tagArr.io.rdata)
  val tgtData =
    Mux(useByp, bypTarget, targetArr.io.rdata)

  val btbHitRaw =
    validArr(qidxR) && tagData === tagOf(qPCR)
  val btbHit    = if (GlbCtrl.useSram) {
    btbHitRaw && !(bypValid && !useByp)
  } else btbHitRaw
  val bhtCnt    = bhtArr(qidxR)
  val isRetBit  = Mux(useByp, bypIsRet, typeArr(qidxR))

  val hasRas   = GlbCtrl.rasSize > 0
  val rasValid = WireDefault(false.B)
  val rasTop   = WireDefault(0.U(ISA.AddrBits.W))
  if (hasRas) {
    val ras = Module(new ReturnAddrStack(GlbCtrl.rasSize))
    ras.io.push     := io.updValid && io.updIsCall
    ras.io.pushAddr := io.updPC + 4.U
    ras.io.pop      := io.updValid && io.updIsRet
    rasValid        := ras.io.topValid
    rasTop          := ras.io.top
  }

  val useRas = btbHit && isRetBit && rasValid
  io.predTaken := btbHit && (useRas || bhtCnt(1))
  io.targetPC  := Mux(useRas, rasTop, tgtData)
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
    typeArr(uidx)  := io.updIsRet
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
