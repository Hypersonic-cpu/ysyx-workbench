package rvproc

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxPath
import rvproc.axi4.AXIBus
import rvproc.axi4.AXI.RespStatus.OKAY

class CacheBox(PipeDepth: Int) extends Module {
  val io = IO(new Bundle {
    val master = Flipped(new AXIBus)
    val simid  = Input(UInt(16.W))
    val flush  = Input(Bool())
  })
}

class CacheDPICBox extends BlackBox with HasBlackBoxPath {
  val io = IO(new Bundle {
    val clock   = Input(Clock())
    val reset   = Input(Reset())
    val id      = Input(UInt(16.W))
    val valid   = Input(Bool())
    val flush   = Input(Bool())
    val addr    = Input(Tp.AddrType())
    val resp    = Output(Tp.InstType())
    val latency = Output(SInt(32.W))
  })
  if (GlbCtrl.debug) {
    addPath(PATH.dpic("CacheDPICBox.sv"))
  } else {
    addPath(PATH.dpic("FakeCacheBox.sv"))
  }
}

class iCacheDummy(PipeDepth: Int) extends CacheBox(PipeDepth) {
  require(PipeDepth > 0)
  val headPtr = RegInit(0.U(log2Ceil(PipeDepth + 1).W))
  val tailPtr = RegInit(0.U(log2Ceil(PipeDepth + 1).W))
  def iotaMod(a: UInt) = Mux(a === PipeDepth.U, 0.U, a + 1.U)
  io.master.ar.ready    := iotaMod(headPtr) =/= tailPtr
  io.master.r.bits.data := 0xbadc0de.U ^ headPtr
  io.master.r.bits.resp := OKAY
  io.master.r.bits.last := true.B
  io.master.r.bits.id   := io.simid
  io.master.r.valid     := true.B
  io.master.b           := DontCare
  io.master.aw          := DontCare
  io.master.w           := DontCare

  when(io.master.ar.fire) {
    headPtr := iotaMod(headPtr)
  }

  when(io.master.r.fire) {
    tailPtr := iotaMod(tailPtr)
  }
}

class iCacheSim(PipeDepth: Int) extends CacheBox(PipeDepth) {
  require(PipeDepth > 0)
  io.master.w  := DontCare
  io.master.b  := DontCare
  assert(!io.master.aw.valid, "Readonly iCache")
  io.master.aw := DontCare

  // val reqRing  = for { i <- 0 until PipeDepth } yield RegInit(false.B)
  val reqRing  = Reg(Vec(PipeDepth + 1, Bool()))
  val respRing = Reg(Vec(PipeDepth + 1, Tp.InstType()))
  val timeRing = Reg(Vec(PipeDepth + 1, SInt(32.W)))
  // Next available pos
  val headPtr  = RegInit(0.U(log2Ceil(PipeDepth + 1).W))
  val tailPtr  = RegInit(0.U(log2Ceil(PipeDepth + 1).W))
  def iotaMod(a: UInt) = Mux(a === PipeDepth.U, 0.U, a + 1.U)

  io.master.ar.ready    := iotaMod(headPtr) =/= tailPtr
  io.master.r.bits.data := respRing(tailPtr)
  io.master.r.bits.resp := OKAY
  io.master.r.bits.last := true.B
  io.master.r.bits.id   := io.simid
  io.master.r.valid     := reqRing(tailPtr) && (timeRing(tailPtr) <= 0.S)

  val icache = Module(new CacheDPICBox)
  icache.io.clock := clock
  icache.io.reset := reset
  icache.io.id    := io.simid
  icache.io.flush := io.flush
  icache.io.addr  := io.master.ar.bits.addr
  icache.io.valid := io.master.ar.fire

  val InfTime = 1000000.S

  timeRing(tailPtr) := timeRing(tailPtr) - Mux(
    reqRing(tailPtr),
    1.S,
    0.S
  )

  val delayedArFire = RegNext(io.master.ar.fire)
  val delayedArHead = RegNext(headPtr)

  when(io.master.ar.fire) {
    // Override
    reqRing(headPtr) := true.B
    headPtr          := iotaMod(headPtr)
  }

  when(delayedArFire) {
    timeRing(delayedArHead) := icache.io.latency - 3.S
    respRing(delayedArHead) := icache.io.resp
  }

  when(io.master.r.fire) {
    reqRing(tailPtr)  := false.B
    timeRing(tailPtr) := InfTime
    tailPtr           := iotaMod(tailPtr)
  }

  when(reset.asBool) {
    for (i <- 0 to PipeDepth) {
      timeRing(i) := InfTime // timeRing(i) - Mux(reqRing(i), 1.S, 0.S)
    }
  }

}
