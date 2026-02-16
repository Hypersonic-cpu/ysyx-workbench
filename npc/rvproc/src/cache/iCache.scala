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

case class iCacheConf(
  addrBits:  Int = 32,
  dataBytes: Int = 1024,
  lineBytes: Int = 16,
  assoc: Int = 1) {
  def numSets  = dataBytes / (lineBytes * assoc)
  def idxBits  = log2Ceil(this.numSets)
  def idxBitHi = this.offBits + this.idxBits - 1
  def idxBitLo = this.offBits
  def offBits  = log2Ceil(lineBytes)
  def tagBits  = addrBits - this.idxBits - this.offBits
  def tagBitHi = addrBits - 1
  def tagBitLo = addrBits - tagBits
}

// Readonly
class iCache(conf: iCacheConf) extends Module {
  require(conf.assoc == 1, "Set assoc unimplemented")
  val io = IO(new Bundle {
    val flushAll = Input(Bool())
    val cpuSide = Flipped(new CPUBus)
    val memSide = new AXIBus
  })

  println(
    s"--> Component iCache : tag[${conf.tagBitHi}:${conf.tagBitLo}] |||"
  )
  println(
    s"--> Component iCache : idx[${conf.idxBitHi}:${conf.idxBitLo}] |||"
  )

  val validArr = Reg(Vec(conf.numSets, Bool())) // WARN: DELAY
  val tagArr   = SyncReadMem(conf.numSets, UInt(conf.tagBits.W))
  val dataArr  = SyncReadMem(conf.numSets, UInt((conf.lineBytes * 8).W))

  val flowing :: waiting :: Nil = Enum(2)
  val state                     = RegInit(flowing)
  val nextState                 = WireInit(flowing)

  val req       = io.cpuSide.ar
  val resp      = io.cpuSide.r
  val tagHit    = Wire(Bool())
  val wordSel   = Wire(Tp.RegType())
  val fillBuf   = Reg(Vec(conf.lineBytes * 8 / ISA.RegBits, Tp.RegType()))
  val willShift = nextState === flowing
  req.ready      := willShift
  resp.valid     := RegNext(tagHit)
  resp.bits.data := RegNext(wordSel)

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

  // Cycle 1 (recv)
  val reqA1 = req.bits.addr
  val reqV1 = req.valid

  val reqA2 = RegEnable(reqA1, willShift)
  val reqV2 = RegEnable(reqV1, willShift)

  // Cycle 2 (comp)
  // Parallel 1
  val tagRead  = tagArr.read(idxOf(reqA1), willShift && reqV1)
  val tagValid = validArr(idxOf(reqA2))
  tagHit := tagRead === tagOf(reqA2) && reqV2

  // Parallel 2
  val lineRead  = dataArr.read(reqA1, willShift && reqV1)
  val lineSplit =
    VecInit.tabulate(conf.lineBytes)(i => lineRead(i * 4 + 3, i * 4))
  wordSel := lineSplit(offOf(reqA2))

  // Cycle 3 (resp)
  val fillFinish = RegNext(io.memSide.r.bits.last)
  nextState := MuxLookup(state, waiting)(
    Seq(
      flowing -> Mux(tagHit || !reqV2, flowing, waiting),
      waiting -> Mux(fillFinish, flowing, waiting)
    )
  )
  state     := nextState

  val fillPtr = RegInit(0.U(conf.offBits.W))
  when(state =/= flowing && io.memSide.r.valid) {
    fillBuf(fillPtr) := io.memSide.r.bits.data
    fillPtr          := fillPtr + 1.U
    assert(
      io.memSide.r.bits.resp === OKAY,
      "Memory error during cache fill"
    )
    when(io.memSide.r.bits.last) {
      assert(
        fillPtr === (conf.lineBytes - 1).U,
        cf"Only got ${fillPtr + 1.U} transactions during fill"
      )

    }
  }.elsewhen(state === flowing) {
    fillPtr := 0.U
  }

  val catData = VecInit(fillBuf.reverse).asUInt
  when(fillFinish) {
    dataArr.write(idxOf(reqA2), catData)
    tagArr.write(idxOf(reqA2), tagOf(reqA2))
  }
}
