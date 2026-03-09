package rvproc.brpred

import chisel3._
import chisel3.util._
import rvproc.{BTFNT, Bimodal, GlbCtrl, ISA, NoPred, Tp}
import rvproc.device.CacheArray

case class BrPredConf(numEntries: Int = 64, numBtbEnt: Int = 64) {
  require(
    numEntries > 0 && (numEntries & (numEntries - 1)) == 0,
    s"BrPredConf: numEntries must be power of 2, got $numEntries"
  )
  require(
    numBtbEnt > 0 && (numBtbEnt & (numBtbEnt - 1)) == 0,
    s"BrPredConf: numBtbEnt must be power of 2, got $numBtbEnt"
  )
  // BHT index (for saturating counters)
  def idxBits    = log2Ceil(numEntries)
  def idxHi      = idxBits + 2 - 1
  // BTB index/tag (for tag + target arrays)
  def btbIdxBits = log2Ceil(numBtbEnt)
  def btbIdxHi   = btbIdxBits + 2 - 1
  def btbTagBits = math.min(ISA.AddrBits - btbIdxBits - 2, 16)
}

// predTaken/targetPC valid ONE cycle after queryPC changes.
abstract class BrPred(val conf: BrPredConf) extends Module {
  val io = IO(new Bundle {
    val queryPC     = Input(Tp.AddrType())
    val predTaken   = Output(Bool())
    val targetPC    = Output(Tp.AddrType())
    val btbHit      = Output(Bool())
    val bhtCnt      = Output(UInt(2.W))
    val updValid    = Input(Bool())
    val updPC       = Input(Tp.AddrType())
    val updTaken    = Input(Bool())
    val updTarget   = Input(Tp.AddrType())
    val updBtbHit   = Input(Bool())
    val updOldCnt   = Input(UInt(2.W))
    val updIsCall   = Input(Bool())
    val updIsRet    = Input(Bool())
    val updIsBranch = Input(Bool())
  })

  // BHT index (for saturating counters)
  protected def idxOf(pc: UInt): UInt = pc(conf.idxHi, 2)
  // BTB index/tag (for tag + target arrays)
  protected def btbIdxOf(pc: UInt): UInt =
    pc(conf.btbIdxHi, 2)
  protected def btbTagOf(pc: UInt): UInt =
    pc(conf.btbIdxHi + conf.btbTagBits, conf.btbIdxHi + 1)
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

  // Valid bit merged into tag MSB to avoid DFF Vec MUX
  val tagArr    = Module(
    new CacheArray(conf.numBtbEnt, conf.btbTagBits + 1)
  )
  val targetArr = Module(
    new CacheArray(conf.numBtbEnt, 32)
  )
  val typeArr   =
    RegInit(VecInit(Seq.fill(conf.numBtbEnt)(false.B)))

  val qidx  = btbIdxOf(io.queryPC)
  val qPCR  = RegNext(io.queryPC)
  val qidxR = btbIdxOf(qPCR)

  tagArr.io.raddr    := qidx
  tagArr.io.ren      := true.B
  targetArr.io.raddr := qidx
  targetArr.io.ren   := true.B

  val uidx       = btbIdxOf(io.updPC)
  val btbUpdWen  =
    io.updValid && (io.updTaken ||
      (!io.updIsBranch && io.updBtbHit))
  val btbUpdWdata = Mux(
    io.updTaken,
    Cat(true.B, btbTagOf(io.updPC)),
    0.U((conf.btbTagBits + 1).W)
  )

  val tagData                                     = if (GlbCtrl.useSram) {
    val bypValid  = RegNext(btbUpdWen)
    val bypIdx    = RegNext(uidx)
    val bypTag    = RegNext(btbUpdWdata)
    val bypTarget = RegNext(io.updTarget)
    val bypIsRet  = RegNext(io.updIsRet)
    val useByp    = bypValid && bypIdx === qidxR
    (
      Mux(useByp, bypTag, tagArr.io.rdata),
      Mux(useByp, bypTarget, targetArr.io.rdata),
      Mux(useByp, bypIsRet, typeArr(qidxR)),
      bypValid,
      useByp
    )
  } else {
    (
      tagArr.io.rdata,
      targetArr.io.rdata,
      typeArr(qidxR),
      false.B,
      false.B
    )
  }
  val (tagDRaw, tgtData, isRetBit, bypValid, useByp) = tagData

  val tagValid  = tagDRaw(conf.btbTagBits)
  val tagD      = tagDRaw(conf.btbTagBits - 1, 0)
  val btbHitRaw =
    tagValid && tagD === btbTagOf(qPCR)
  val btbHit    = if (GlbCtrl.useSram) {
    btbHitRaw && !(bypValid && !useByp)
  } else btbHitRaw

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
  io.targetPC  := Mux(isRetBit && rasValid, rasTop, tgtData)
  io.btbHit    := btbHit
  io.bhtCnt    := 0.U

  tagArr.io.waddr    := uidx
  tagArr.io.wdata    := btbUpdWdata
  tagArr.io.wen      := btbUpdWen
  targetArr.io.waddr := uidx
  targetArr.io.wdata := io.updTarget
  targetArr.io.wen   := io.updValid && io.updTaken
  when(io.updValid && io.updTaken) {
    typeArr(uidx) := io.updIsRet
  }
}

class BimodalPredictor(conf: BrPredConf) extends BrPred(conf) {

  // Valid bit merged into tag MSB to avoid DFF Vec MUX
  val tagArr    = Module(
    new CacheArray(conf.numBtbEnt, conf.btbTagBits + 1)
  )
  val targetArr = Module(
    new CacheArray(conf.numBtbEnt, ISA.AddrBits)
  )
  val typeArr   =
    RegInit(VecInit(Seq.fill(conf.numBtbEnt)(false.B)))

  // BHT: SyncReadMem eliminates the wide combinational
  // MUX of a DFF Vec, breaking the timing-critical path
  // through the saturating counter read.
  val bhtMem  = SyncReadMem(conf.numEntries, UInt(2.W))
  val bhtQidx = idxOf(io.queryPC)
  val bhtRd   = bhtMem.read(bhtQidx)

  val qPCR     = RegNext(io.queryPC)
  // BTB index (may differ from BHT index width)
  val btbQidx  = btbIdxOf(io.queryPC)
  val btbQidxR = btbIdxOf(qPCR)
  val bhtQidxR = idxOf(qPCR)

  tagArr.io.raddr    := btbQidx
  tagArr.io.ren      := true.B
  targetArr.io.raddr := btbQidx
  targetArr.io.ren   := true.B

  // BHT write + bypass (defined early so bhtCnt
  // is available for predTaken computation below)
  val bhtUidx = idxOf(io.updPC)
  def incrSat(c: UInt): UInt =
    Mux(c === 3.U, 3.U, c + 1.U)
  def decrSat(c: UInt): UInt =
    Mux(c === 0.U, 0.U, c - 1.U)
  val bhtWen    =
    io.updValid && (io.updBtbHit || io.updTaken)
  val bhtWdata  = Mux(
    io.updTaken && !io.updBtbHit,
    2.U,
    Mux(
      io.updTaken,
      incrSat(io.updOldCnt),
      decrSat(io.updOldCnt)
    )
  )
  when(bhtWen) { bhtMem.write(bhtUidx, bhtWdata) }
  // Bypass: concurrent read+write at same index
  val bypBhtV   = RegNext(bhtWen, false.B)
  val bypBhtI   = RegNext(bhtUidx)
  val bypBhtD   = RegNext(bhtWdata)
  val useBhtByp =
    bypBhtV && bypBhtI === bhtQidxR
  val bhtCnt    = Mux(useBhtByp, bypBhtD, bhtRd)

  val btbUidx    = btbIdxOf(io.updPC)
  val btbUpdWen  =
    io.updValid && (io.updTaken ||
      (!io.updIsBranch && io.updBtbHit))
  val btbUpdWdata = Mux(
    io.updTaken,
    Cat(true.B, btbTagOf(io.updPC)),
    0.U((conf.btbTagBits + 1).W)
  )

  val tagData                                     = if (GlbCtrl.useSram) {
    val bypValid  = RegNext(btbUpdWen)
    val bypIdx    = RegNext(btbUidx)
    val bypTag    = RegNext(btbUpdWdata)
    val bypTarget = RegNext(io.updTarget)
    val bypIsRet  = RegNext(io.updIsRet)
    val useByp    = bypValid && bypIdx === btbQidxR
    (
      Mux(useByp, bypTag, tagArr.io.rdata),
      Mux(useByp, bypTarget, targetArr.io.rdata),
      Mux(useByp, bypIsRet, typeArr(btbQidxR)),
      bypValid,
      useByp
    )
  } else {
    (
      tagArr.io.rdata,
      targetArr.io.rdata,
      typeArr(btbQidxR),
      false.B,
      false.B
    )
  }
  val (tagDRaw, tgtData, isRetBit, bypValid, useByp) = tagData

  val tagValid  = tagDRaw(conf.btbTagBits)
  val tagD      = tagDRaw(conf.btbTagBits - 1, 0)
  val btbHitRaw =
    tagValid && tagD === btbTagOf(qPCR)
  val btbHit    = if (GlbCtrl.useSram) {
    btbHitRaw && !(bypValid && !useByp)
  } else btbHitRaw

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
  io.targetPC  := Mux(isRetBit && rasValid, rasTop, tgtData)
  io.btbHit    := btbHit
  io.bhtCnt    := bhtCnt

  tagArr.io.waddr    := btbUidx
  tagArr.io.wdata    := btbUpdWdata
  tagArr.io.wen      := btbUpdWen
  targetArr.io.waddr := btbUidx
  targetArr.io.wdata := io.updTarget
  targetArr.io.wen   := io.updValid && io.updTaken
  when(io.updValid && io.updTaken) {
    typeArr(btbUidx) := io.updIsRet
  }
}

object BrPred {
  def apply(): Option[BrPred] = {
    val conf = BrPredConf(GlbCtrl.bpEntries, GlbCtrl.btbEntries)
    GlbCtrl.bpType match {
      case NoPred  => None
      case BTFNT   =>
        Some(Module(new BTFNTPredictor(conf)))
      case Bimodal =>
        Some(Module(new BimodalPredictor(conf)))
    }
  }
}
