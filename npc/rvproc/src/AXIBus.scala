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

  object RespStatus extends ChiselEnum {
    val OKAY, EXOKAY, SLVERR, DECERR = Value
  }

  object BurstOpts extends ChiselEnum {
    val FIXED, INCR, WRAP, RSV = Value
  }
}

// To device
class ArGroup extends Bundle {
  val addr  = Tp.AddrType()
  val size  = AXI.SizeType()
  val len   = AXI.LenType()
  val burst = AXI.BurstOpts()
  val id    = AXI.IdType()
}

// To device
class AwGroup extends Bundle {
  val addr  = Tp.AddrType()
  val size  = AXI.SizeType()
  val len   = AXI.LenType()
  val burst = AXI.BurstOpts()
  val id    = AXI.IdType()
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

class AXIReadChannel extends Bundle {
  val ar = Decoupled(new ArGroup)
  val r  = Flipped(Decoupled(new RGroup))
}

class AXIWriteChannel extends Bundle {
  val aw = Decoupled(new AwGroup)
  val w  = Decoupled(new WGroup)
  val b  = Flipped(Decoupled(new BGroup))
}

class AXIBus extends Bundle {
  val ar = Decoupled(new ArGroup)
  val r  = Flipped(Decoupled(new RGroup))
  val aw = Decoupled(new AwGroup)
  val w  = Decoupled(new WGroup)
  val b  = Flipped(Decoupled(new BGroup))
}

object AXIPortPassing {
  // For connection between two masters, right side is inner
  // (source, like IFU.iMemMaster) and left side is outer
  // (like Core.iMemMaster).
  def apply[T <: Data](dst: AXIBus, src: AXIBus): Unit = {
    PortPassing(dst.ar, src.ar, DriveDir.RightDrivesLeft)
    PortPassing(dst.r, src.r, DriveDir.LeftDrivesRight)
    PortPassing(dst.aw, src.aw, DriveDir.RightDrivesLeft)
    PortPassing(dst.w, src.w, DriveDir.RightDrivesLeft)
    PortPassing(dst.b, src.b, DriveDir.LeftDrivesRight)
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

  for (i <- 0 until N) {
    val selectThis = i.U === usingIdx
    io.hosts(i).r.valid  := selectThis && io.device.r.valid
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

  for (i <- 0 until N) {
    val selectThis = i.U === usingIdx
    io.hosts(i).b.valid  := selectThis && io.device.b.valid
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

class XBarRead(N: Int, amap: Seq[UInt => Bool]) extends Module {
  require(N > 0 && amap.length == N, "amap length must match N")
  val io = IO(new Bundle {
    val host    = Flipped(new AXIReadChannel)
    val devices = Vec(N, new AXIReadChannel)
  })

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
  assert(
    io.host.ar.valid Implies (!decodeErr),
    cf"Encoutering un-mapped read @ raddr ${io.host.ar.bits.addr}%x"
  )
  val usingIdx  = Mux(state === idle, tarIdx, serveId)

  val pivot = io.devices(usingIdx)
  pivot <> io.host
  io.host.r.bits.resp := Mux(
    state === error,
    AXI.RespStatus.DECERR,
    pivot.r.bits.resp
  )
  io.host.r.valid     := Mux(state === error, true.B, pivot.r.valid)

  for (i <- 0 until N) {
    val selectThis = i.U === usingIdx;
    io.devices(i).ar.valid := selectThis && io.host.ar.valid
    io.devices(i).ar.bits  := io.host.ar.bits
    io.devices(i).r.ready  := selectThis && io.host.r.ready
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
  dontTouch(io)
}

class XBarWrite(N: Int, amap: Seq[UInt => Bool]) extends Module {
  require(N > 0 && amap.length == N, "amap length must match N")

  val io = IO(new Bundle {
    val host    = Flipped(new AXIWriteChannel)
    val devices = Vec(N, new AXIWriteChannel)
  })

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
  assert(
    io.host.aw.valid Implies (!decodeErr),
    cf"Encoutering un-mapped write "
      + cf"waddr ${io.host.aw.bits.addr}%x data ${io.host.w.bits.data}%x "
  )
  val usingIdx  = Mux(state === idle, tarIdx, serveId)

  val pivot = io.devices(usingIdx)
  pivot <> io.host
  io.host.b.bits.resp := Mux(
    state === error,
    AXI.RespStatus.DECERR,
    pivot.b.bits.resp
  )
  io.host.b.valid     := Mux(state === error, true.B, pivot.b.valid)

  for (i <- 0 until N) {
    val selectThis = i.U === usingIdx;
    io.devices(i).aw.valid := selectThis && io.host.aw.valid
    io.devices(i).aw.bits  := io.host.aw.bits
    io.devices(i).w.valid  := selectThis && io.host.w.valid
    io.devices(i).w.bits   := io.host.w.bits
    io.devices(i).b.ready  := selectThis && io.host.b.ready
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
  dontTouch(io)
}

class AXIXBar(N: Int, amap: Seq[UInt => Bool]) extends Module {
  require(amap.length == N, "amap length must match N")

  val io = IO(new Bundle {
    val host    = Flipped(new AXIBus)
    val devices = Vec(N, new AXIBus)
  })

  val readChannel  = Module(new XBarRead(N, amap))
  val writeChannel = Module(new XBarWrite(N, amap))
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
