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
import rvproc.pmu.PfSwPMU

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
  def tagVBits    = tagBits // valid bit is a separate DFF, not in tag
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

// Readonly, 2-cycle pipeline: recv -> tag-compare + word-select
// Valid bit is a separate DFF array (requires reset).
// Tag/data backend: SyncReadMem (Tiny) or SRAM BlackBox (Extended).
class iCache(
  conf:         iCacheConf,
  withPrefetch: Boolean = false)
    extends Module {
  require(conf.assoc == 1, "Set assoc unimplemented")
  val io = IO(new Bundle {
    val flushAll = Input(Bool())
    val cpuSide  = Flipped(new AXIBus)
    val memSide  = new AXIBus
  })

  conf.printConf()

  import rvproc.device.CacheArray

  // Arrays - CacheArray selects SyncReadMem or SRAM
  val tagArr  = Module(
    new CacheArray(conf.numSets, conf.tagBits)
  )
  val dataArr = Module(
    new CacheArray(conf.numSets, conf.lineBytes * 8)
  )

  // Valid bits: separate DFF array with reset
  val validArr =
    RegInit(VecInit(Seq.fill(conf.numSets)(false.B)))

  val flowing :: waiting :: memreq :: flushing :: Nil =
    Enum(4)

  // validArr is all-false at reset, so start in flowing.
  val state     = RegInit(flowing)
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

  val tagHit     = Wire(Bool())
  val fillBuf    =
    Reg(Vec(conf.lineBytes * 8 / ISA.RegBits, Tp.RegType()))
  // Avoid read-write conflict on fill completion.
  val fillFinish = RegNext(
    io.memSide.r.bits.last
      && io.memSide.r.fire && state === waiting
  )

  val flushPending = RegInit(false.B)
  val fillAddr     = Reg(Tp.AddrType())
  val isPrefetch   = RegInit(false.B)
  when(io.flushAll) { flushPending := true.B }

  val pf = if (withPrefetch)
    Some(Module(new NextLinePrefetcher(conf)))
  else None

  val pfIdle = WireInit(false.B)
  pf.foreach { p =>
    p.io.flush     := io.flushAll
    p.io.snoopDone := false.B
    p.io.snoopAddr := DontCare
    p.io.consumed  := false.B
  }

  // Direct computation bypasses MuxLookup for nextState.
  val willShift = Wire(Bool())
  req.ready := willShift

  val missServe = RegInit(false.B)
  val missData  = RegInit(0.U(32.W))
  val fillError = RegInit(OKAY)

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

  // Cycle 1 (recv) - issue array reads
  val reqA1 = req.bits.addr
  val reqV1 = req.valid

  val reqA2  = Reg(Tp.AddrType())
  val reqV2  = RegInit(false.B)
  val isPfC2 = RegInit(false.B)

  // Prefetch injection: when pipeline C1 is idle, use pf addr
  val pfInject = WireInit(false.B)
  val pfAddr   = WireInit(0.U(conf.addrBits.W))
  pf.foreach { p =>
    pfAddr := p.io.addr
    pfIdle := p.io.pending &&
      !reqV2 && !req.valid && !flushPending &&
      state === flowing
    pfInject := pfIdle && willShift
  }

  val c1Addr = Mux(pfInject, pfAddr, reqA1)
  val c1Valid = reqV1 || pfInject

  tagArr.io.raddr  := idxOf(c1Addr)
  tagArr.io.ren    := willShift && c1Valid
  dataArr.io.raddr := idxOf(c1Addr)
  dataArr.io.ren   := willShift && c1Valid

  when(willShift) {
    reqA2  := c1Addr
    reqV2  := c1Valid
    isPfC2 := pfInject
  }.otherwise {
    reqV2  := false.B
    isPfC2 := false.B
  }

  // Cycle 2: tag compare + word select + respond
  val tagRead = tagArr.io.rdata
  tagHit := tagRead === tagOf(reqA2) &&
    validArr(idxOf(reqA2)) && reqV2 && !isPfC2

  val pfHitC2 = tagRead === tagOf(reqA2) &&
    validArr(idxOf(reqA2)) && reqV2 && isPfC2
  val pfMissC2 = reqV2 && isPfC2 && !pfHitC2

  val demandMiss = reqV2 && !isPfC2 && !tagHit
  // Demand miss during prefetch fill: saves address so we
  // can serve it after the prefetch fill completes.
  val pfDemandPend = RegInit(false.B)
  val pfDemandAddr = Reg(Tp.AddrType())

  willShift :=
    (state === flowing || (state === waiting &&
      isPrefetch && !pfDemandPend)) &&
      !fillFinish && !flushPending &&
      (tagHit || pfHitC2 || !reqV2)

  // Word select directly from SRAM output (no register)
  val lineRead  = dataArr.io.rdata
  val lineSplit =
    VecInit.tabulate(conf.lineTrans)(i =>
      lineRead(
        (i + 1) * ISA.RegBits - 1,
        i * ISA.RegBits
      )
    )
  val wordSel   = WireInit(lineSplit(ithOf(reqA2)))

  resp.valid     := missServe || tagHit
  resp.bits.data := Mux(missServe, missData, wordSel)
  resp.bits.resp := Mux(missServe, fillError, OKAY)

  when(resp.fire) {
    printf(
      cf"IC resp: ms=$missServe tH=$tagHit "
        + cf"rA2=$reqA2%x fA=$fillAddr%x "
        + cf"d=$missData%x pf=$isPrefetch\n"
    )
  }
  when(fillFinish) {
    printf(
      cf"IC fill: fA=$fillAddr%x pf=$isPrefetch "
        + cf"w0=${fillBuf(0.U)}%x w1=${fillBuf(1.U)}%x "
        + cf"w2=${fillBuf(2.U)}%x w3=${fillBuf(3.U)}%x\n"
    )
  }
  when(state === flowing && nextState === memreq) {
    printf(
      cf"IC miss: reqA2=$reqA2%x pf=$pfIdle\n"
    )
  }

  // FSM
  when(state === waiting && isPrefetch && demandMiss) {
    pfDemandPend := true.B
    pfDemandAddr := reqA2
  }
  when((pfDemandPend && fillFinish) || io.flushAll) {
    pfDemandPend := false.B
  }

  nextState := MuxLookup(state, waiting)(
    Seq(
      flowing  -> Mux(
        demandMiss || pfMissC2,
        memreq,
        Mux(flushPending, flushing, flowing)
      ),
      memreq   -> Mux(
        io.memSide.ar.fire,
        waiting,
        memreq
      ),
      waiting  -> Mux(
        fillFinish,
        Mux(
          pfDemandPend,
          memreq,
          Mux(flushPending, flushing, flowing)
        ),
        waiting
      ),
      flushing -> flowing
    )
  )
  state     := nextState

  // Flush valid bits (1-cycle clear)
  when(state === flushing) {
    validArr.foreach(_ := false.B)
  }
  when(nextState === flushing) { flushPending := false.B }

  when(state === flowing && nextState === memreq) {
    fillAddr   := reqA2
    isPrefetch := isPfC2
  }
  when(pfDemandPend && fillFinish) {
    fillAddr   := pfDemandAddr
    isPrefetch := false.B
  }
  pf.foreach { p =>
    p.io.consumed  := pfHitC2 || pfMissC2
    p.io.snoopDone := fillFinish && !isPrefetch
    p.io.snoopAddr := fillAddr
  }

  // Cache line fill
  val fillPtr = RegInit(0.U(conf.lineTBits.W))
  when(state === waiting && io.memSide.r.valid) {
    fillBuf(fillPtr) := io.memSide.r.bits.data
    fillPtr          := fillPtr + 1.U
    when(io.memSide.r.bits.resp =/= OKAY) {
      fillError := io.memSide.r.bits.resp
    }
    when(io.memSide.r.bits.last) {
      val goldenPtr = (conf.lineTrans - 1).U
      assert(
        fillPtr === goldenPtr,
        cf"Got ${fillPtr}+1 transactions during fill,"
          + cf" expect ${goldenPtr}+1\n"
      )
    }
  }.elsewhen(state === memreq && io.memSide.ar.fire) {
    fillPtr   := 0.U
    fillError := OKAY
  }

  io.memSide.r.ready       := true.B
  io.memSide.ar.valid      := state === memreq
  io.memSide.ar.bits.addr  := blkOf(fillAddr)
  io.memSide.ar.bits.len   := (conf.lineTrans - 1).U
  io.memSide.ar.bits.burst := INCR
  io.memSide.ar.bits.id    := 0.U   // iCache
  io.memSide.ar.bits.size  := 0x2.U // log2(4)
  io.memSide.w             := DontCare
  io.memSide.aw            := DontCare
  io.memSide.b             := DontCare

  // Centralized array write ports
  val catData = fillBuf.asUInt

  // Tag: fill completion only (flush uses validArr DFF)
  tagArr.io.wen   := fillFinish && fillError === OKAY
  tagArr.io.waddr := idxOf(fillAddr)
  tagArr.io.wdata := tagOf(fillAddr)

  when(fillFinish && fillError === OKAY) {
    validArr(idxOf(fillAddr)) := true.B
  }

  dataArr.io.wen   := fillFinish && fillError === OKAY
  dataArr.io.waddr := idxOf(fillAddr)
  dataArr.io.wdata := catData

  when(fillFinish && !isPrefetch) {
    missServe := true.B
    missData  := fillBuf(ithOf(fillAddr))
    assert(
      !resp.fire,
      "Transaction (resp) during fill\n"
    )
  }.elsewhen(resp.fire) {
    missServe := false.B
  }

  // Prefetch usefulness tracking
  val pfUsefulSig = WireInit(false.B)
  if (withPrefetch) {
    val isPrefetched = RegInit(
      VecInit(Seq.fill(conf.numSets)(false.B))
    )
    when(fillFinish && fillError === OKAY) {
      isPrefetched(idxOf(fillAddr)) := isPrefetch
    }
    when(state === flushing) {
      isPrefetched.foreach(_ := false.B)
    }
    // demand hit on a prefetch-filled line
    pfUsefulSig := tagHit &&
      isPrefetched(idxOf(reqA2))
    when(pfUsefulSig) {
      isPrefetched(idxOf(reqA2)) := false.B
    }
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
    val pmu        = Module(new iCacheSwPMU)
    val delayedReq = RegNext(req.fire)
    pmu.io.reset    := reset
    pmu.io.clock    := clock
    pmu.io.resp     := resp.fire
    pmu.io.respHit  := resp.fire && tagHit
    pmu.io.respAddr := reqA2
    pmu.io.reqAddr  := req.bits.addr
    pmu.io.req      := req.fire
    pmu.io.id       := 0.U

    if (withPrefetch) {
      val pfPmu = Module(new PfSwPMU)
      pfPmu.io.clock    := clock
      pfPmu.io.reset    := reset
      pfPmu.io.pfIssued := fillFinish && isPrefetch
      pfPmu.io.pfHitC2  := pfHitC2
      pfPmu.io.pfUseful := pfUsefulSig
      pfPmu.io.pfAddr   := fillAddr
    }
  }
}
