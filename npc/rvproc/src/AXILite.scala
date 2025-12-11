package rvproc.axi4

import rvproc._

import chisel3._
import chisel3.util._
import java.nio.BufferUnderflowException

import rvproc.PortPassing.DriveDir
import rvproc.BitMath._

/** NOTE: Ready should NOT wait for valid, Valid could be asserted
  * by/after ready.
  */

object ReadRespStatus extends ChiselEnum {
  // place holder
  val PHldr0, PHldr1, PHldr2, PHldr3 = Value
}

object WriteRespStatus extends ChiselEnum {
  val PHldr0, PHldr1, PHldr2, PHldr3 = Value
}

// To host
class RGroup extends Bundle {
  val data = Tp.RegType()
  val resp = ReadRespStatus()
}

// To device
class ArGroup extends Bundle {
  val addr = Tp.AddrType()
}

// To device
class AwGroup extends Bundle {
  val addr = Tp.AddrType()
}

// To device
class WGroup extends Bundle {
  val data = Tp.RegType()
  val strb = UInt((ISA.RegBits / 8).W)
}

// To host
class BGroup extends Bundle {
  val resp = WriteRespStatus()
}

class AXILite extends Bundle {
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
  def apply[T <: Data](dst: AXILite, src: AXILite): Unit = {
    PortPassing(dst.ar, src.ar, DriveDir.RightDrivesLeft)
    PortPassing(dst.r, src.r, DriveDir.LeftDrivesRight)
    PortPassing(dst.aw, src.aw, DriveDir.RightDrivesLeft)
    PortPassing(dst.w, src.w, DriveDir.RightDrivesLeft)
    PortPassing(dst.b, src.b, DriveDir.LeftDrivesRight)
  }
}

object AXIPortConnect {
  def apply[T <: Data](dst: AXILite, src: AXILite): Unit = {
    dst.ar <> src.ar
    dst.r <> src.r
    dst.aw <> src.aw
    dst.w <> src.w
    dst.b <> src.b
  }
}

class AXIArbiter(N: Int) extends Module {
  val io = IO(new Bundle {
    val hosts  = Vec(N, Flipped(new AXILite))
    val device = new AXILite
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
    val selectThis = state === serve && i.U === serveId
    val issueThis  = state === idle && i.U === validIdx
    // Response
    io.hosts(i).r.valid  := selectThis && io.device.r.valid
    io.hosts(i).r.bits   := io.device.r.bits
    io.hosts(i).b.valid  := selectThis && io.device.b.valid
    io.hosts(i).b.bits   := io.device.b.bits
    // Request
    io.hosts(i).ar.ready := issueThis && io.device.ar.ready
    io.hosts(i).aw.ready := issueThis && io.device.aw.ready
    io.hosts(i).w.ready  := issueThis && io.device.w.ready
  }

  when(state === idle && validReqs.orR) {
    serveId := validIdx
  }

  // MuxCase
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
    val host    = Flipped(new AXILite)
    val devices = Vec(N, new AXILite)
  })

  val IdxWidth:  Int  = log2Ceil(N)
  def IdxType(): UInt = UInt(log2Ceil(N).W)

  val idle :: serve :: Nil = Enum(2)

  val state   = RegInit(idle)
  val serveId = Reg(IdxType())

  val inputRd = io.host.ar.valid
  val inputWr = io.host.aw.valid
  val inputVa = inputRd || inputWr
  val inputAd =
    Mux(io.host.aw.valid, io.host.aw.bits.addr, io.host.ar.bits.addr)
  assert(
    io.host.aw.valid Excludes io.host.ar.valid,
    "ar and aw both valid"
  )
  val tarIdx  = MuxCase(
    0.U(IdxWidth.W),
    amap map { entry =>
      (
        (inputAd >= entry.lo.U(ISA.AddrBits.W) &&
          inputAd < entry.hi.U(ISA.AddrBits.W))
          -> entry.id.U
      )
    }
  )
  
  val nextState = MuxLookup(state, idle)(
    Seq(
      idle -> Mux(inputVa, serve, idle), 
      serve -> Mux(
        
        )
      )
    )

}
