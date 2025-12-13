package rvproc.axi4

import rvproc._

import chisel3._
import chisel3.util._
import java.nio.BufferUnderflowException

import rvproc.PortPassing.DriveDir
import rvproc.BitMath._

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

// object AXIPortConnect {
//   def apply[T <: Data](dst: AXIBus, src: AXIBus): Unit = {
//     dst.ar <> src.ar
//     dst.r <> src.r
//     dst.aw <> src.aw
//     dst.w <> src.w
//     dst.b <> src.b
//   }
// }
//
// FIXME: 
// TODO: ID and burst
class AXIArbiter(N: Int) extends Module {
  val io = IO(new Bundle {
    val hosts  = Vec(N, Flipped(new AXIBus))
    val device = new AXIBus
  })
  val IdxWidth: Int = log2Ceil(N)
  def IdxType(): UInt = UInt(log2Ceil(N).W)

  val idle :: serve :: Nil = Enum(2)

  val state   = RegInit(idle)
  val serveId = Reg(IdxType())

  val validReads = Cat(VecInit(io.hosts map (_.ar.valid)).reverse)
  val validWrite = Cat(VecInit(io.hosts map (_.aw.valid)).reverse)
  val validReqs  = validReads | validWrite
  val validIdx   = PriorityEncoder(validReqs)
  val usingIdx   = Mux(state === idle, validIdx, serveId)

  val pivot = io.hosts(usingIdx)
  pivot <> io.device
  for (i <- 0 until N) {
    // Can change to usingIdx
    val selectThis = i.U === usingIdx
    // Response
    io.hosts(i).r.valid  := selectThis && io.device.r.valid
    io.hosts(i).r.bits   := io.device.r.bits
    io.hosts(i).b.valid  := selectThis && io.device.b.valid
    io.hosts(i).b.bits   := io.device.b.bits
    // Request
    io.hosts(i).ar.ready := selectThis && io.device.ar.ready
    io.hosts(i).aw.ready := selectThis && io.device.aw.ready
    io.hosts(i).w.ready  := selectThis && io.device.w.ready
  }

  when(state === idle && validReqs.orR) {
    serveId := validIdx
  }

  val nextState = MuxLookup(state, idle)(
    Seq(
      idle  -> Mux(validReqs.orR, serve, idle),
      serve -> Mux(
        (io.device.r.valid && pivot.r.ready)
          || (io.device.b.valid && pivot.b.ready),
        idle,
        serve
      )
    )
  )
  state := nextState
}

case class AddrMap(lo: BigInt, hi: BigInt, id: Int)

class AXIXBar(N: Int, amap: Seq[AddrMap]) extends Module {
  require(N > 0 && amap.nonEmpty, "Empty address mapping")
  val maxId = (amap map (_.id)).max
  require(maxId < N, "Max MapId exceeds N")
  require(amap forall (_.id >= 0), "Negative Id")

  val io = IO(new Bundle {
    val host    = Flipped(new AXIBus)
    val devices = Vec(N, new AXIBus)
  })

  val IdxWidth:  Int  = log2Ceil(N)
  def IdxType(): UInt = UInt(log2Ceil(N).W)

  val idle :: serve :: rdce :: wdce :: Nil = Enum(4)

  val state   = RegInit(idle)
  val serveId = Reg(IdxType())

  val inputRd   = io.host.ar.valid
  val inputWr   = io.host.aw.valid
  val inputVa   = inputRd || inputWr
  val inputAd   =
    Mux(io.host.aw.valid, io.host.aw.bits.addr, io.host.ar.bits.addr)
  assert(
    io.host.aw.valid Excludes io.host.ar.valid,
    "ar and aw both valid"
  )
  val tarIdxExt = MuxCase(
    1.U(IdxWidth.W),
    amap map { entry =>
      (
        (inputAd >= entry.lo.U(ISA.AddrBits.W) &&
          inputAd < entry.hi.U(ISA.AddrBits.W))
          -> entry.id.U ## 0.U
      )
    }
  )
  val tarIdx    = tarIdxExt >> 1.U
  val decodeErr = tarIdxExt(0)
  assert(
    io.host.ar.valid Implies (!decodeErr),
    cf"Encoutering un-mapped read @ raddr ${io.host.ar.bits.addr}%x"
  )
  assert(
    io.host.aw.valid Implies (!decodeErr),
    cf"Encoutering un-mapped write "
      + cf"waddr ${io.host.aw.bits.addr}%x data ${io.host.w.bits.data}%x "
  )
  val usingIdx  = Mux(state === idle, tarIdx, serveId)

  val pivot = io.devices(usingIdx)
  pivot <> io.host
  io.host.r.bits.resp := Mux(
    state === rdce,
    AXI.RespStatus.DECERR,
    pivot.r.bits.resp
  )
  io.host.b.bits.resp := Mux(
    state === wdce,
    AXI.RespStatus.DECERR,
    pivot.b.bits.resp
  )
  io.host.r.valid     := Mux(state === rdce, true.B, pivot.r.valid)
  io.host.b.valid     := Mux(state === wdce, true.B, pivot.b.valid)

  for (i <- 0 until N) {
    val selectThis = i.U === usingIdx;
    io.devices(i).ar.valid := selectThis && io.host.ar.valid
    io.devices(i).ar.bits  := io.host.ar.bits
    io.devices(i).aw.valid := selectThis && io.host.aw.valid
    io.devices(i).aw.bits  := io.host.aw.bits
    io.devices(i).w.valid  := selectThis && io.host.w.valid
    io.devices(i).w.bits   := io.host.w.bits
    // Response
    io.devices(i).r.ready  := selectThis && io.host.r.ready
    io.devices(i).b.ready  := selectThis && io.host.b.ready
  }

  when(state === idle && inputVa) {
    serveId := tarIdx
  }

  val nextState = MuxLookup(state, idle)(
    Seq(
      idle  -> Mux(
        inputVa,
        Mux(decodeErr, Mux(io.host.ar.valid, rdce, wdce), serve),
        idle
      ),
      serve -> Mux(
        (pivot.r.valid && io.host.r.ready)
          || (pivot.b.valid && io.host.b.ready),
        idle,
        serve
      ),
      rdce  -> Mux(io.host.r.ready, idle, rdce),
      wdce  -> Mux(io.host.b.ready, idle, wdce)
    )
  )
  state := nextState
  dontTouch(io)
}
