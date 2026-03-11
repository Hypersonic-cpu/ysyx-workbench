package rvproc

import chisel3._
import chisel3.util._
import rvproc.BusType._

sealed trait BusType
object BusType {
  case object SingleCyc  extends BusType
  case object MultiCyc   extends BusType
  case object Pipeline   extends BusType
  case object PipeReg    extends BusType
  case object OutOfOrder extends BusType
  val all = Seq(SingleCyc, MultiCyc, Pipeline, PipeReg, OutOfOrder)
}

object BusConnect {

  // 2-register skid buffer: breaks BOTH forward and backward
  // combinational chains. src.ready, dst.valid, dst.bits all
  // depend only on registers. 100% throughput, 1-cycle latency.
  private def skidBuf[T <: Data](
    src: DecoupledIO[T],
    dst: DecoupledIO[T]
  ): Unit = {
    val mainV = RegInit(false.B)
    val mainB = Reg(chiselTypeOf(src.bits))
    val skidV = RegInit(false.B)
    val skidB = Reg(chiselTypeOf(src.bits))

    src.ready := !skidV
    dst.valid := mainV
    dst.bits  := mainB

    when(dst.fire) {
      when(src.fire) {
        mainB := src.bits
      }.elsewhen(skidV) {
        mainB := skidB
        skidV := false.B
      }.otherwise {
        mainV := false.B
      }
    }.elsewhen(src.fire) {
      when(!mainV) {
        mainV := true.B
        mainB := src.bits
      }.otherwise {
        skidV := true.B
        skidB := src.bits
      }
    }
  }

  private def skidBufFlush[T <: Data](
    src:   DecoupledIO[T],
    dst:   DecoupledIO[T],
    flush: Bool
  ): Unit = {
    val mainV = RegInit(false.B)
    val mainB = Reg(chiselTypeOf(src.bits))
    val skidV = RegInit(false.B)
    val skidB = Reg(chiselTypeOf(src.bits))

    src.ready := !skidV || flush
    dst.valid := mainV && !flush
    dst.bits  := mainB

    when(flush) {
      mainV := false.B
      skidV := false.B
    }.elsewhen(dst.fire) {
      when(src.fire) {
        mainB := src.bits
      }.elsewhen(skidV) {
        mainB := skidB
        skidV := false.B
      }.otherwise {
        mainV := false.B
      }
    }.elsewhen(src.fire) {
      when(!mainV) {
        mainV := true.B
        mainB := src.bits
      }.otherwise {
        skidV := true.B
        skidB := src.bits
      }
    }
  }

  def apply[T <: Data](
    src:   DecoupledIO[T],
    dst:   DecoupledIO[T],
    busTp: BusType = SingleCyc
  ) = {
    busTp match {
      case SingleCyc  => { dst <> src }
      case MultiCyc   => { dst <> src }
      case Pipeline   => skidBuf(src, dst)
      case PipeReg    => {
        src.ready := dst.ready
        dst.valid := RegEnable(src.valid, dst.ready)
        dst.bits  := RegEnable(src.bits, dst.ready)
      }
      case OutOfOrder => { dst <> Queue(src, 16) }
    }
  }

  def apply[T <: Data](
    src:   DecoupledIO[T],
    dst:   DecoupledIO[T],
    busTp: BusType,
    flush: Bool
  ): Unit = {
    busTp match {
      case Pipeline   => skidBufFlush(src, dst, flush)
      case PipeReg    => {
        src.ready := dst.ready || flush
        val regV = RegInit(false.B)
        when(flush) {
          regV := false.B
        }.elsewhen(dst.ready) {
          regV := src.valid
        }
        dst.valid := regV
        dst.bits  := RegEnable(src.bits, dst.ready || flush)
      }
      case _          => apply(src, dst, busTp)
    }
  }
}

object RegDstPacket {
  def apply[T <: Data](
    fwdsrc: FwBundle,
    signal: DecodeForward,
    output: RegDstBundle
  ): Unit = {
    output.valid := fwdsrc.valid
    output.gprFw := fwdsrc.gprFw
    output.gprDt := fwdsrc.gprDt
    output.csrRd := signal.csrRd
    output.csrWE := signal.csrWE
    output.gprRd := signal.gprRd
    output.gprWE := signal.gprWE
  }
}

object PortPassing {
  object DriveDir extends Enumeration {
    val LeftDrivesRight, RightDrivesLeft = Value
  }

  def apply[T <: Data](
    lhs: DecoupledIO[T],
    rhs: DecoupledIO[T],
    dir: DriveDir.Value
  ): Unit = {
    dir match {
      case DriveDir.LeftDrivesRight => {
        rhs.valid := lhs.valid
        rhs.bits  := lhs.bits
        lhs.ready := rhs.ready
      }
      case DriveDir.RightDrivesLeft => {
        lhs.valid := rhs.valid
        lhs.bits  := rhs.bits
        rhs.ready := lhs.ready
      }
    }
  }
}
