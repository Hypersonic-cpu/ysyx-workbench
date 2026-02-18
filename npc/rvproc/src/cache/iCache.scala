package rvproc.cache

import chisel3._
import chisel3.util._
import chisel3.assert.Assert
import rvproc.axi4._
import rvproc.BusType._
import rvproc.BusConnect
import rvproc.BitMath._
import rvproc.Tp
import rvproc.ISA
import rvproc.axi4.AXI.RespStatus.OKAY
import rvproc.axi4.AXI.BurstOpts._
import rvproc.GlbCtrl.{debug, sta}

case class iCacheConf(
  addrBits:  Int = 32,
  dataBytes: Int = 1024,
  lineBytes: Int = 16,
  assoc: Int = 1) {
  def numSets   = dataBytes / (lineBytes * assoc)
  def idxBits   = log2Ceil(this.numSets)
  def idxBitHi  = this.offBits + this.idxBits - 1
  def idxBitLo  = this.offBits
  def offBits   = log2Ceil(lineBytes)
  def tagBits   = addrBits - this.idxBits - this.offBits
  def tagBitHi  = addrBits - 1
  def tagBitLo  = addrBits - tagBits
  def lineTrans = this.lineBytes / (this.addrBits / 8)
  def lineTBits = log2Ceil(this.lineTrans)
}

class iCachePMU extends Module {
  val io         = IO(new Bundle {
    val access = Input(Bool())
    val hit    = Input(Bool())
    val regidx = Input(UInt(2.W))
    val regout = Output(UInt(32.W))
  })
  val accCountHi = RegInit(0.U(32.W))
  val accCountLo = RegInit(0.U(32.W))
  val hitCountHi = RegInit(0.U(32.W))
  val hitCountLo = RegInit(0.U(32.W))
  when(io.access) {
    accCountLo := accCountLo + 1.U
    accCountHi := accCountHi + Mux(accCountLo.andR, 1.U, 0.U)
  }
  when(io.hit) {
    hitCountLo := hitCountLo + 1.U
    hitCountHi := hitCountHi + Mux(accCountLo.andR, 1.U, 0.U)
  }
  assert(io.hit Implies io.access, "Hit but not access ?");
  io.regout := MuxLookup(io.regidx, 0.U)(
    Seq(
      0.U -> accCountLo,
      1.U -> accCountHi,
      2.U -> hitCountLo,
      3.U -> hitCountHi
    )
  )
}

// Readonly
class iCache(conf: iCacheConf) extends Module {
  require(conf.assoc == 1, "Set assoc unimplemented")
  val io = IO(new Bundle {
    val flushAll = Input(Bool())
    val cpuSide  = Flipped(new CPUBus)
    val memSide  = new AXIBus
  })

  println(
    s"--> iCache Addr : [${conf.tagBitHi}: tag :${conf.tagBitLo}]"
      + s"[${conf.idxBitHi}: idx :${conf.idxBitLo}][${conf.offBits - 1}: off :0]"
  )

  val validArr = RegInit(VecInit(Seq.fill(conf.numSets)(false.B)))
  val tagArr   = SyncReadMem(conf.numSets, UInt(conf.tagBits.W))
  val dataArr  = SyncReadMem(conf.numSets, UInt((conf.lineBytes * 8).W))

  val flowing :: waiting :: memreq :: Nil = Enum(3)

  val state     = RegInit(flowing)
  val nextState = WireInit(flowing)

  val req        = io.cpuSide.ar
  val resp       = io.cpuSide.r
  val tagHit     = Wire(Bool())
  val wordSel    = Wire(Tp.RegType())
  val fillBuf    = Reg(Vec(conf.lineBytes * 8 / ISA.RegBits, Tp.RegType()))
  // Avoid SyncReadMem read-write conflict: don't accept new requests on
  // the cycle the fill completes (write and read would hit the same index).
  // Gate with state===waiting to ignore spurious AXI R responses that leak
  // through the arbiter/crossbar during non-waiting states.
  val fillFinish = RegNext(
    io.memSide.r.bits.last && io.memSide.r.fire && state === waiting
  )
  val willShift  = nextState === flowing && !fillFinish
  req.ready := willShift
  val hitRespV  = RegNext(tagHit)
  val hitRespD  = RegNext(wordSel)
  val missServe = RegInit(false.B)
  val missData  = RegInit(0.U(32.W))
  resp.valid     := missServe || hitRespV
  resp.bits.data := Mux(missServe, missData, hitRespD)

  // Should not issue this request to cache if LSU has no position
  assert(
    resp.valid Implies resp.ready,
    "iCache response but host not ready"
  )

  io.cpuSide.aw           := DontCare
  io.cpuSide.b            := DontCare
  io.cpuSide.ar.bits.size := DontCare
  // io.cpuSide.ar.ready

  def idxOf(x: UInt) = x(conf.idxBitHi, conf.idxBitLo)
  def tagOf(x: UInt) = x(conf.tagBitHi, conf.tagBitLo)
  def offOf(x: UInt) = x(conf.offBits - 1, 0)
  def blkOf(x: UInt) =
    x(conf.tagBitHi, conf.offBits) ## 0.U(conf.offBits.W)
  def ithOf(x: UInt) = x(conf.offBits - 1, ISA.WordShift)

  // Cycle 1 (recv)
  val reqA1 = req.bits.addr
  val reqV1 = req.valid

  val reqA2 = RegEnable(reqA1, willShift)
  // Clear reqV2 when pipeline stalls to prevent stale tag comparison
  // from triggering a spurious miss after fill completion.
  val reqV2 = RegInit(false.B)
  when(willShift) { reqV2 := reqV1 }.otherwise { reqV2 := false.B }

  // Cycle 2 (comp)
  // Parallel 1
  val tagRead  = tagArr.read(idxOf(reqA1), willShift && reqV1)
  val tagValid = validArr(idxOf(reqA2))
  tagHit := tagRead === tagOf(reqA2) && tagValid && reqV2

  // printf(cf"iCache Tag Read = ${tagRead}%x\n")
  // Parallel 2
  val lineRead  = dataArr.read(idxOf(reqA1), willShift && reqV1)
  val lineSplit =
    VecInit.tabulate(conf.lineTrans)(i =>
      lineRead((i + 1) * ISA.RegBits - 1, i * ISA.RegBits)
    )
  wordSel := lineSplit(ithOf(reqA2))

  // Cycle 3 (resp)
  nextState := MuxLookup(state, waiting)(
    Seq(
      flowing -> Mux(tagHit || !reqV2, flowing, memreq),
      memreq  -> Mux(io.memSide.ar.fire, waiting, memreq),
      waiting -> Mux(fillFinish, flowing, waiting)
    )
  )
  state     := nextState

  val fillPtr = RegInit(0.U(conf.lineTBits.W))
  when(state === waiting && io.memSide.r.valid) {
    fillBuf(fillPtr) := io.memSide.r.bits.data
    fillPtr          := fillPtr + 1.U
    assert(
      io.memSide.r.bits.resp === OKAY,
      "Memory error during cache fill"
    )
    when(io.memSide.r.bits.last) {
      val goldenPtr = (conf.lineTrans - 1).U
      assert(
        fillPtr === goldenPtr,
        cf"Got ${fillPtr}+1 transactions during fill, expect ${goldenPtr}+1\n"
      )

    }
  }.elsewhen(state === memreq && io.memSide.ar.fire) {
    fillPtr := 0.U
  }

  when(state === flowing && nextState === memreq) {
    printf(cf"iCache Miss : addr ${reqA2}%x\n")
  }

  when(io.cpuSide.r.fire) {
    printf(
      cf"iCache Hit : addr ${RegNext(reqA2)}%x data ${io.cpuSide.r.bits.data}%x\n"
    )
  }

  // MemSide Req
  io.memSide.r.ready       := true.B
  io.memSide.ar.valid      := state === memreq
  io.memSide.ar.bits.addr  := blkOf(reqA2)
  io.memSide.ar.bits.len   := (conf.lineTrans - 1).U
  io.memSide.ar.bits.burst := INCR
  io.memSide.ar.bits.id    := 0.U   // iCache
  io.memSide.ar.bits.size  := 0x2.U // log2(4)
  // TODO: Proper ID
  io.memSide.w             := DontCare
  io.memSide.aw            := DontCare
  io.memSide.b             := DontCare

  val catData = fillBuf.asUInt
  when(fillFinish) {
    dataArr.write(idxOf(reqA2), catData)
    tagArr.write(idxOf(reqA2), tagOf(reqA2))
    validArr(idxOf(reqA2)) := true.B
    missServe              := true.B
    missData               := fillBuf(ithOf(reqA2))
    assert(!resp.fire, "Transaction (resp) during fill\n")
  }.elsewhen(resp.fire) {
    missServe := false.B
  }

  // fence.i: invalidate all lines after any in-flight fill completes
  val flushPending = RegInit(false.B)
  when(io.flushAll) { flushPending := true.B }
  when(flushPending && state === flowing && !fillFinish) {
    for (i <- 0 until conf.numSets) { validArr(i) := false.B }
    flushPending := false.B
  }

  if (debug) {
    dontTouch(reqA1)
    dontTouch(reqA2)
    dontTouch(reqV1)
    dontTouch(reqV2)
    dontTouch(tagRead)
    dontTouch(wordSel)
    dontTouch(lineSplit)
    dontTouch(lineRead)
  }

  if (!sta) {
    val pmu = Module(new iCachePMU)
    pmu.io.access := resp.fire
    pmu.io.hit    := resp.fire && hitRespV
    pmu.io.regidx := io.cpuSide.ar.bits.addr(3, 2)
    dontTouch(pmu.io.regout)
  }
}
