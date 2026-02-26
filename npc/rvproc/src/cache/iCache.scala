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
import rvproc.pmu.iCacheSwPMU

case class iCacheConf(
  addrBits:  Int = 32,
  dataBytes: Int = 1024,
  lineBytes: Int = 16,
  assoc: Int = 1) {
  def numSets     = dataBytes / (lineBytes * assoc)
  def idxBits     = log2Ceil(this.numSets)
  def idxBitHi    = this.offBits + this.idxBits - 1
  def idxBitLo    = this.offBits
  def offBits     = log2Ceil(lineBytes)
  def tagBits     = addrBits - this.idxBits - this.offBits
  def tagVBits    = tagBits + 1 // +1 valid bit
  def tagBitHi    = addrBits - 1
  def tagBitLo    = addrBits - tagBits
  def lineTrans   = this.lineBytes / (this.addrBits / 8)
  def lineTBits   = log2Ceil(this.lineTrans)
  def printConf() = {
    println(
      s"iCache : [${this.tagBitHi}: tag :${this.tagBitLo}]"
        + s"[${this.idxBitHi}: idx :${this.idxBitLo}]"
        + s"[${this.offBits - 1}: off :0]"
        + s" Assoc ${this.assoc} #Sets ${this.numSets}"
        + s" BlkSize ${this.lineBytes}"
        + s" TagV ${this.tagVBits}b"
    )
  }
}

// Readonly, 3-cycle pipeline: recv → tag-compare → word-select
// Valid bit merged into tag array (MSB).
// Backend: SyncReadMem (sramlib=false) or SRAM BlackBox (=true).
class iCache(conf: iCacheConf) extends Module {
  require(conf.assoc == 1, "Set assoc unimplemented")
  val io = IO(new Bundle {
    val flushAll = Input(Bool())
    val cpuSide  = Flipped(new AXIBus)
    val memSide  = new AXIBus
  })

  conf.printConf()

  import rvproc.device.CacheArray

  // Arrays — CacheArray selects SyncReadMem or SRAM
  val tagArr  = Module(
    new CacheArray(conf.numSets, conf.tagVBits)
  )
  val dataArr = Module(
    new CacheArray(conf.numSets, conf.lineBytes * 8)
  )

  def mkTagV(tag:   UInt, v: Bool): UInt = v ## tag
  def tagOfTV(tv:   UInt): UInt = tv(conf.tagBits - 1, 0)
  def validOfTV(tv: UInt): Bool = tv(conf.tagBits).asBool

  val flowing :: waiting :: memreq :: flushing :: Nil =
    Enum(4)

  // Start in flushing: arrays have no reset, so we
  // must write valid=0 to every set before accepting reqs.
  val state     = RegInit(flushing)
  val nextState = WireInit(flowing)

  io.cpuSide.w  := DontCare
  io.cpuSide.b  := DontCare
  io.cpuSide.aw := DontCare

  val req  = io.cpuSide.ar
  val resp = io.cpuSide.r

  assert(
    req.valid Implies req.bits.id === 0.U,
    "iCache recv non-IFU req"
  )
  assert(
    req.valid Implies req.bits.len === 0.U,
    "iCache recv burst req"
  )
  assert(
    req.valid Implies req.bits.size === 2.U,
    "iCache recv non-4byte req"
  ) // 4 Bytes
  resp.bits.id   := 0.U
  resp.bits.last := true.B
  resp.bits.resp := OKAY

  val tagHit  = Wire(Bool())
  val fillBuf =
    Reg(Vec(conf.lineBytes * 8 / ISA.RegBits, Tp.RegType()))
  // Avoid read-write conflict on fill completion.
  val fillFinish = RegNext(
    io.memSide.r.bits.last
      && io.memSide.r.fire && state === waiting
  )
  // Accept C1 requests only in steady-state flowing.
  val willShift =
    state === flowing && nextState === flowing && !fillFinish
  req.ready := willShift

  val hitRespV  = RegNext(tagHit)
  val missServe = RegInit(false.B)
  val missData  = RegInit(0.U(32.W))

  assert(
    resp.valid Implies resp.ready,
    "iCache response but host not ready"
  )

  def idxOf(x: UInt) = x(conf.idxBitHi, conf.idxBitLo)
  def tagOf(x: UInt) = x(conf.tagBitHi, conf.tagBitLo)
  def offOf(x: UInt) = x(conf.offBits - 1, 0)
  def blkOf(x: UInt) =
    x(conf.tagBitHi, conf.offBits) ## 0.U(conf.offBits.W)
  def ithOf(x: UInt) = x(conf.offBits - 1, ISA.WordShift)

  // ── Cycle 1 (recv) — issue array reads ─────────────────
  val reqA1 = req.bits.addr
  val reqV1 = req.valid

  tagArr.io.raddr  := idxOf(reqA1)
  tagArr.io.ren    := willShift && reqV1
  dataArr.io.raddr := idxOf(reqA1)
  dataArr.io.ren   := willShift && reqV1

  val reqA2 = RegEnable(reqA1, willShift)
  val reqV2 = RegInit(false.B)
  when(willShift) { reqV2 := reqV1 }
    .otherwise { reqV2 := false.B }

  // ── Cycle 2 (tag compare) ──────────────────────────────
  val tagRead = tagArr.io.rdata
  tagHit := tagOfTV(tagRead) === tagOf(reqA2) &&
    validOfTV(tagRead) && reqV2

  // Register line data for cycle 3 (breaks SRAM→mux path).
  val lineRead  = dataArr.io.rdata
  val lineReadR = RegNext(lineRead)
  val reqA3     = RegNext(reqA2)

  // ── Cycle 3 (word select + respond) ────────────────────
  val lineSplit =
    VecInit.tabulate(conf.lineTrans)(i =>
      lineReadR(
        (i + 1) * ISA.RegBits - 1,
        i * ISA.RegBits
      )
    )
  val wordSel = WireInit(lineSplit(ithOf(reqA3)))

  resp.valid     := missServe || hitRespV
  resp.bits.data := Mux(missServe, missData, wordSel)

  // ── State machine ──────────────────────────────────────
  val flushPending = RegInit(false.B)
  when(io.flushAll) { flushPending := true.B }

  val flushCtr =
    RegInit(0.U(conf.idxBits.W))

  nextState := MuxLookup(state, waiting)(
    Seq(
      flowing -> Mux(
        tagHit || !reqV2,
        Mux(flushPending, flushing, flowing),
        memreq
      ),
      memreq -> Mux(
        io.memSide.ar.fire,
        waiting,
        memreq
      ),
      waiting -> Mux(
        fillFinish,
        Mux(flushPending, flushing, flowing),
        waiting
      ),
      flushing -> Mux(
        flushCtr === (conf.numSets - 1).U,
        flowing,
        flushing
      )
    )
  )
  state := nextState

  // Flush counter
  when(state === flushing) {
    when(flushCtr === (conf.numSets - 1).U) {
      flushCtr := 0.U
    }.otherwise {
      flushCtr := flushCtr + 1.U
    }
  }
  when(nextState === flushing) { flushPending := false.B }

  // ── Fill logic ─────────────────────────────────────────
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
        cf"Got ${fillPtr}+1 transactions during fill,"
          + cf" expect ${goldenPtr}+1\n"
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
      cf"iCache Hit : addr ${reqA3}%x"
        + cf" data ${io.cpuSide.r.bits.data}%x\n"
    )
  }

  // ── MemSide ────────────────────────────────────────────
  io.memSide.r.ready       := true.B
  io.memSide.ar.valid      := state === memreq
  io.memSide.ar.bits.addr  := blkOf(reqA2)
  io.memSide.ar.bits.len   := (conf.lineTrans - 1).U
  io.memSide.ar.bits.burst := INCR
  io.memSide.ar.bits.id    := 0.U   // iCache
  io.memSide.ar.bits.size  := 0x2.U // log2(4)
  io.memSide.w             := DontCare
  io.memSide.aw            := DontCare
  io.memSide.b             := DontCare

  // ── Centralized array write ports ──────────────────────
  val catData = fillBuf.asUInt

  // Tag: fill completion OR flush (mutually exclusive)
  tagArr.io.wen :=
    fillFinish || (state === flushing)
  tagArr.io.waddr :=
    Mux(fillFinish, idxOf(reqA2), flushCtr)
  tagArr.io.wdata := Mux(
    fillFinish,
    mkTagV(tagOf(reqA2), true.B),
    0.U(conf.tagVBits.W)
  )

  // Data: fill completion only
  dataArr.io.wen   := fillFinish
  dataArr.io.waddr := idxOf(reqA2)
  dataArr.io.wdata := catData

  // ── Fill completion bookkeeping ────────────────────────
  when(fillFinish) {
    missServe := true.B
    missData  := fillBuf(ithOf(reqA2))
    assert(
      !resp.fire,
      "Transaction (resp) during fill\n"
    )
  }.elsewhen(resp.fire) {
    missServe := false.B
  }

  // ── Debug ──────────────────────────────────────────────
  if (debug) {
    dontTouch(reqA1)
    dontTouch(reqA2)
    dontTouch(reqA3)
    dontTouch(reqV1)
    dontTouch(reqV2)
    dontTouch(tagRead)
    dontTouch(wordSel)
    dontTouch(lineSplit)
    dontTouch(lineRead)
    dontTouch(lineReadR)
  }

  if (!sta) {
    val pmu        = Module(new iCacheSwPMU)
    val delayedReq = RegNext(req.fire)
    pmu.io.reset    := reset
    pmu.io.clock    := clock
    pmu.io.resp     := resp.fire
    pmu.io.respHit  := resp.fire && hitRespV
    pmu.io.respAddr := reqA3
    pmu.io.reqAddr  := req.bits.addr
    pmu.io.req      := req.fire
    pmu.io.id       := 0.U
  }
}
