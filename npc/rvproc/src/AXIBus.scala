package rvproc.axi4

import rvproc._

import chisel3._
import chisel3.util._
import java.nio.BufferUnderflowException

import rvproc.PortPassing.DriveDir
import rvproc.BitMath._
import rvproc.ISA.AddrBits
import java.time.chrono.ThaiBuddhistEra

object AXI {
  // Bytes per chunk, log(64/8) = 3
  def SizeType() = UInt(3.W)
  // Numbers = AxLEN + 1
  def LenType()  = UInt(8.W)

  def IdType() = UInt(4.W)
  def LockType() = UInt(1.W)
  def CacheType() = UInt(4.W)
  def ProtType() = UInt(3.W)
  def QosType() = UInt(4.W)

  object RespStatus extends ChiselEnum {
    val OKAY, EXOKAY, SLVERR, DECERR = Value
  }

  object BurstOpts extends ChiselEnum {
    val FIXED, INCR, WRAP, RSV = Value
  }
}

// To device
class ArGroup(val isFullExt: Boolean = false) extends Bundle {
  val addr  = Tp.AddrType()
  val size  = AXI.SizeType()
  val len   = AXI.LenType()
  val burst = AXI.BurstOpts()
  val id    = AXI.IdType()
  val lock  = if (isFullExt) Some(AXI.LockType()) else None
  val cache = if (isFullExt) Some(AXI.CacheType()) else None
  val prot  = if (isFullExt) Some(AXI.ProtType()) else None
  val qos   = if (isFullExt) Some(AXI.QosType()) else None
}

// To device
class AwGroup(val isFullExt: Boolean = false) extends Bundle {
  val addr  = Tp.AddrType()
  val size  = AXI.SizeType()
  val len   = AXI.LenType()
  val burst = AXI.BurstOpts()
  val id    = AXI.IdType()
  val lock  = if (isFullExt) Some(AXI.LockType()) else None
  val cache = if (isFullExt) Some(AXI.CacheType()) else None
  val prot  = if (isFullExt) Some(AXI.ProtType()) else None
  val qos   = if (isFullExt) Some(AXI.QosType()) else None
}

// To device
class WGroup extends Bundle {
  val data = Tp.RegType()
  val strb = UInt((ISA.RegBits / 8).W)
  val last = Bool()
}

// To host
class RGroup extends Bundle {
  val data = Tp.RegType()
  val resp = AXI.RespStatus()
  val last = Bool()
  val id   = AXI.IdType()
}

// To host
class BGroup extends Bundle {
  val resp = AXI.RespStatus()
  val id   = AXI.IdType()
}

class AXIReadChannel(val isFullExt: Boolean = false)
    extends Bundle {
  val ar = Decoupled(new ArGroup(isFullExt))
  val r  = Flipped(Decoupled(new RGroup))
}

class AXIWriteChannel(val isFullExt: Boolean = false)
    extends Bundle {
  val aw = Decoupled(new AwGroup(isFullExt))
  val w  = Decoupled(new WGroup)
  val b  = Flipped(Decoupled(new BGroup))
}

class AXIBus(val isFullExt: Boolean = false) extends Bundle {
  val ar = Decoupled(new ArGroup(isFullExt))
  val r  = Flipped(Decoupled(new RGroup))
  val aw = Decoupled(new AwGroup(isFullExt))
  val w  = Decoupled(new WGroup)
  val b  = Flipped(Decoupled(new BGroup))
}

object AXIPortPassing {
  // For connection between two masters, right side is inner
  // (source, like IFU.iMemMaster) and left side is outer
  // (like Core.iMemMaster).
  def apply[T <: Data](dst: AXIBus, src: AXIBus): Unit = {
    PortPassing(
      dst.ar: ReadyValidIO[_ <: Record],
      src.ar: ReadyValidIO[_ <: Record],
      DriveDir.RightDrivesLeft
    )
    PortPassing(
      dst.r: ReadyValidIO[_ <: Record],
      src.r: ReadyValidIO[_ <: Record],
      DriveDir.LeftDrivesRight
    )
    PortPassing(
      dst.aw: ReadyValidIO[_ <: Record],
      src.aw: ReadyValidIO[_ <: Record],
      DriveDir.RightDrivesLeft
    )
    PortPassing(
      dst.w: ReadyValidIO[_ <: Record],
      src.w: ReadyValidIO[_ <: Record],
      DriveDir.RightDrivesLeft
    )
    PortPassing(
      dst.b: ReadyValidIO[_ <: Record],
      src.b: ReadyValidIO[_ <: Record],
      DriveDir.LeftDrivesRight
    )
  }
}

class ArbiterRead(N: Int) extends Module {
  val io = IO(new Bundle {
    val hosts  = Vec(N, Flipped(new AXIReadChannel))
    val device = new AXIReadChannel
  })
  val IdxWidth: Int = log2Ceil(N)
  def IdxType(): UInt = UInt(log2Ceil(N).W)

  val idle :: serve :: Nil = Enum(2)

  val state   = RegInit(idle)
  val serveId = Reg(IdxType())

  val validReqs = Cat(VecInit(io.hosts map (_.ar.valid)))
  val validIdx  = (N - 1).U - PriorityEncoder(validReqs)
  val hasReq    = validReqs.orR
  val usingIdx  = Mux(state === idle, validIdx, serveId)

  val pivot = io.hosts(usingIdx)
  io.device.ar.valid := pivot.ar.valid
  io.device.ar.bits  := pivot.ar.bits
  io.device.r.ready  := pivot.r.ready

  // Gate r.valid with serving (registered) to break
  // PriorityEncoder -> r.valid combinational path.
  val serving = state === serve
  for (i <- 0 until N) {
    val selectThis = i.U === usingIdx
    io.hosts(i).r.valid  :=
      serving && selectThis && io.device.r.valid
    io.hosts(i).r.bits   := io.device.r.bits
    io.hosts(i).ar.ready := selectThis && io.device.ar.ready
  }

  when(state === idle && hasReq) {
    serveId := validIdx
  }

  val nextState = MuxLookup(state, idle)(
    Seq(
      idle  -> Mux(hasReq, serve, idle),
      serve -> Mux(
        io.device.r.valid && pivot.r.ready && io.device.r.bits.last,
        idle,
        serve
      )
    )
  )
  state := nextState
}

class ArbiterWrite(N: Int) extends Module {
  val io = IO(new Bundle {
    val hosts  = Vec(N, Flipped(new AXIWriteChannel))
    val device = new AXIWriteChannel
  })
  val IdxWidth: Int = log2Ceil(N)
  def IdxType(): UInt = UInt(log2Ceil(N).W)

  val idle :: serve :: Nil = Enum(2)

  val state   = RegInit(idle)
  val serveId = Reg(IdxType())

  val validReqs = Cat(VecInit(io.hosts map (_.aw.valid)))
  val validIdx  = (N - 1).U - PriorityEncoder(validReqs)
  val hasReq    = validReqs.orR
  val usingIdx  = Mux(state === idle, validIdx, serveId)

  val pivot = io.hosts(usingIdx)
  io.device.aw.valid := pivot.aw.valid
  io.device.aw.bits  := pivot.aw.bits
  io.device.w.valid  := pivot.w.valid
  io.device.w.bits   := pivot.w.bits
  io.device.b.ready  := pivot.b.ready

  val serving = state === serve
  for (i <- 0 until N) {
    val selectThis = i.U === usingIdx
    io.hosts(i).b.valid  :=
      serving && selectThis && io.device.b.valid
    io.hosts(i).b.bits   := io.device.b.bits
    io.hosts(i).aw.ready := selectThis && io.device.aw.ready
    io.hosts(i).w.ready  := selectThis && io.device.w.ready
  }

  when(state === idle && hasReq) {
    serveId := validIdx
  }

  val nextState = MuxLookup(state, idle)(
    Seq(
      idle  -> Mux(hasReq, serve, idle),
      serve -> Mux(
        io.device.b.valid && pivot.b.ready,
        idle,
        serve
      )
    )
  )
  state := nextState
}

class AXIArbiter(N: Int) extends Module {
  val io = IO(new Bundle {
    val hosts  = Vec(N, Flipped(new AXIBus))
    val device = new AXIBus
  })

  val readArb  = Module(new ArbiterRead(N))
  val writeArb = Module(new ArbiterWrite(N))

  for (i <- 0 until N) {
    readArb.io.hosts(i).ar <> io.hosts(i).ar
    readArb.io.hosts(i).r <> io.hosts(i).r
    writeArb.io.hosts(i).aw <> io.hosts(i).aw
    writeArb.io.hosts(i).w <> io.hosts(i).w
    writeArb.io.hosts(i).b <> io.hosts(i).b
  }

  readArb.io.device.ar <> io.device.ar
  readArb.io.device.r <> io.device.r
  writeArb.io.device.aw <> io.device.aw
  writeArb.io.device.w <> io.device.w
  writeArb.io.device.b <> io.device.b
}

class XBarRead(
  N:        Int,
  amap:     Seq[UInt => Bool],
  slverrDf: Boolean = true)
    extends Module {
  require(N > 0 && amap.length == N, "amap length must match N")
  val io = IO(new Bundle {
    val host    = Flipped(new AXIReadChannel)
    val devices = Vec(N, new AXIReadChannel)
  })

  val errResp =
    if (slverrDf) AXI.RespStatus.SLVERR
    else AXI.RespStatus.DECERR

  val IdxWidth:  Int  = log2Ceil(N)
  def IdxType(): UInt = UInt(log2Ceil(N).W)

  val idle :: serve :: error :: Nil = Enum(3)

  val state   = RegInit(idle)
  val serveId = Reg(IdxType())

  val inputVa = io.host.ar.valid
  val inputAd = io.host.ar.bits.addr

  val tarIdxExt = MuxCase(
    1.U(IdxWidth.W),
    amap.zipWithIndex map { case (matchFn, i) =>
      matchFn(inputAd) -> (i.U ## 0.U(1.W))
    }
  )
  val tarIdx    = tarIdxExt(IdxWidth, 1)
  val decodeErr = tarIdxExt(0)
  val usingIdx  = Mux(state === idle, tarIdx, serveId)

  val pivot = io.devices(usingIdx)
  pivot <> io.host
  io.host.r.bits.resp := Mux(
    state === error,
    errResp,
    pivot.r.bits.resp
  )
  io.host.r.valid     := Mux(state === error, true.B, pivot.r.valid)

  // Decode error: accept from host, don't forward
  val inDecErr =
    state === idle && decodeErr && inputVa
  val devGate  = !inDecErr && state =/= error
  when(inDecErr) { io.host.ar.ready := true.B }
  when(state === error) {
    io.host.ar.ready := false.B
  }

  for (i <- 0 until N) {
    val selectThis = i.U === usingIdx;
    io.devices(i).ar.valid :=
      selectThis && io.host.ar.valid && devGate
    io.devices(i).ar.bits  := io.host.ar.bits
    io.devices(i).r.ready  :=
      selectThis && io.host.r.ready && devGate
  }

  when(state === idle && inputVa) {
    serveId := tarIdx
  }

  state := MuxLookup(state, idle)(
    Seq(
      idle  -> Mux(
        inputVa,
        Mux(decodeErr, error, serve),
        idle
      ),
      serve -> Mux(
        (pivot.r.valid && io.host.r.ready),
        idle,
        serve
      ),
      error -> Mux(io.host.r.ready, idle, error)
    )
  )
  if (GlbCtrl.debug) { dontTouch(io) }
}

class XBarWrite(
  N:        Int,
  amap:     Seq[UInt => Bool],
  slverrDf: Boolean = true)
    extends Module {
  require(N > 0 && amap.length == N, "amap length must match N")

  val io = IO(new Bundle {
    val host    = Flipped(new AXIWriteChannel)
    val devices = Vec(N, new AXIWriteChannel)
  })

  val errResp =
    if (slverrDf) AXI.RespStatus.SLVERR
    else AXI.RespStatus.DECERR

  val IdxWidth:  Int  = log2Ceil(N)
  def IdxType(): UInt = UInt(log2Ceil(N).W)

  val idle :: serve :: error :: Nil = Enum(3)

  val state   = RegInit(idle)
  val serveId = Reg(IdxType())

  val inputVa   = io.host.aw.valid
  val inputAd   = io.host.aw.bits.addr
  val tarIdxExt = MuxCase(
    1.U(IdxWidth.W),
    amap.zipWithIndex map { case (matchFn, i) =>
      matchFn(inputAd) -> (i.U ## 0.U(1.W))
    }
  )
  val tarIdx    = tarIdxExt(IdxWidth, 1)
  val decodeErr = tarIdxExt(0)
  val usingIdx  = Mux(state === idle, tarIdx, serveId)

  val pivot = io.devices(usingIdx)
  pivot <> io.host
  io.host.b.bits.resp := Mux(
    state === error,
    errResp,
    pivot.b.bits.resp
  )
  io.host.b.valid     := Mux(state === error, true.B, pivot.b.valid)

  val inDecErr =
    state === idle && decodeErr && inputVa
  val devGate  = !inDecErr && state =/= error
  when(inDecErr) {
    io.host.aw.ready := true.B
    io.host.w.ready  := true.B
  }
  when(state === error) {
    io.host.aw.ready := false.B
    io.host.w.ready  := true.B
  }

  for (i <- 0 until N) {
    val selectThis = i.U === usingIdx;
    io.devices(i).aw.valid :=
      selectThis && io.host.aw.valid && devGate
    io.devices(i).aw.bits  := io.host.aw.bits
    io.devices(i).w.valid  :=
      selectThis && io.host.w.valid && devGate
    io.devices(i).w.bits   := io.host.w.bits
    io.devices(i).b.ready  :=
      selectThis && io.host.b.ready && devGate
  }

  when(state === idle && inputVa) {
    serveId := tarIdx
  }

  val nextState = MuxLookup(state, idle)(
    Seq(
      idle  -> Mux(
        inputVa,
        Mux(decodeErr, error, serve),
        idle
      ),
      serve -> Mux(
        (pivot.b.valid && io.host.b.ready),
        idle,
        serve
      ),
      error -> Mux(io.host.b.ready, idle, error)
    )
  )
  state := nextState
  if (GlbCtrl.debug) { dontTouch(io) }
}

class AXIXBar(
  N:        Int,
  amap:     Seq[UInt => Bool],
  slverrDf: Boolean = true)
    extends Module {
  require(amap.length == N, "amap length must match N")

  val io = IO(new Bundle {
    val host    = Flipped(new AXIBus)
    val devices = Vec(N, new AXIBus)
  })

  val readChannel  = Module(new XBarRead(N, amap, slverrDf))
  val writeChannel = Module(
    new XBarWrite(N, amap, slverrDf)
  )
  readChannel.io.host.ar <> io.host.ar
  readChannel.io.host.r <> io.host.r
  writeChannel.io.host.aw <> io.host.aw
  writeChannel.io.host.w <> io.host.w
  writeChannel.io.host.b <> io.host.b

  for (i <- 0 until N) {
    readChannel.io.devices(i).ar <> io.devices(i).ar
    readChannel.io.devices(i).r <> io.devices(i).r
    writeChannel.io.devices(i).aw <> io.devices(i).aw
    writeChannel.io.devices(i).w <> io.devices(i).w
    writeChannel.io.devices(i).b <> io.devices(i).b
  }
}

// class CpuRdReq extends Bundle {
//   val addr = Tp.AddrType()
//   val size = AXI.SizeType()
// }
//
// class CpuRdResp extends Bundle {
//   val data = Tp.RegType()
// }
//
// class CpuWrReq extends Bundle {
//   val addr = Tp.AddrType()
//   val data = Tp.RegType()
//   val size = AXI.SizeType()
//   val strb = UInt((ISA.AddrBits / 8).W)
// }
//
// class CpuWrResp extends Bundle {}
//
// class CPUBus extends Bundle {
//   val ar = Decoupled(new CpuRdReq)
//   val r  = Flipped(Decoupled(new CpuRdResp))
//   val aw = Decoupled(new CpuWrReq)
//   val b  = Flipped(Decoupled(new CpuWrResp))
// }
