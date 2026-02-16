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
import rvproc.GlbCtrl.debug

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

  val validArr = Reg(Vec(conf.numSets, Bool())) // WARN: DELAY
  val tagArr   = SyncReadMem(conf.numSets, UInt(conf.tagBits.W))
  val dataArr  = SyncReadMem(conf.numSets, UInt((conf.lineBytes * 8).W))

  val flowing :: waiting :: memreq :: Nil = Enum(3)

  val state     = RegInit(flowing)
  val nextState = WireInit(flowing)

  val req       = io.cpuSide.ar
  val resp      = io.cpuSide.r
  val tagHit    = Wire(Bool())
  val wordSel   = Wire(Tp.RegType())
  val fillBuf   = Reg(Vec(conf.lineBytes * 8 / ISA.RegBits, Tp.RegType()))
  val willShift = nextState === flowing
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

  // Cycle 1 (recv)
  val reqA1 = req.bits.addr
  val reqV1 = req.valid

  val reqA2 = RegEnable(reqA1, willShift)
  val reqV2 = RegEnable(reqV1, willShift)

  // Cycle 2 (comp)
  // Parallel 1
  val tagRead  = tagArr.read(idxOf(reqA1), willShift && reqV1)
  val tagValid = validArr(idxOf(reqA2))
  tagHit := tagRead === tagOf(reqA2) && tagValid && reqV2

  // printf(cf"iCache Tag Read = ${tagRead}%x\n")
  // Parallel 2
  val lineRead  = dataArr.read(reqA1, willShift && reqV1)
  val lineSplit =
    VecInit.tabulate(conf.lineBytes)(i => lineRead(i * 4 + 3, i * 4))
  wordSel := lineSplit(offOf(reqA2))

  // Cycle 3 (resp)
  val fillFinish = RegNext(io.memSide.r.bits.last && io.memSide.r.valid)
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
        cf"Got ${fillPtr} + 1 transactions during fill, expect ${goldenPtr}\n"
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
      cf"iCache Hit : addr ${reqA2}%x data ${io.cpuSide.r.bits.data}%x"
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

  val catData = VecInit(fillBuf.reverse).asUInt
  when(fillFinish) {
    dataArr.write(idxOf(reqA2), catData)
    tagArr.write(idxOf(reqA2), tagOf(reqA2))
    validArr(idxOf(reqA2)) := true.B
    missServe              := true.B
    missData               := catData(offOf(reqA2))
    assert(!resp.fire, "Transaction (resp) during fill\n")
  }.elsewhen(resp.fire) {
    missServe := false.B
  }

  if (debug) {
    dontTouch(reqA1)
    dontTouch(reqA2)
    dontTouch(reqV1)
    dontTouch(reqV2)
    dontTouch(tagRead)
    dontTouch(wordSel)
    dontTouch(lineSplit)
  }

}
