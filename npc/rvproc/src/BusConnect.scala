package rvproc

import chisel3._
import chisel3.util._
import rvproc.BusType._

sealed trait BusType
object BusType {
  case object SingleCyc  extends BusType
  case object MultiCyc   extends BusType
  case object Pipeline   extends BusType
  case object OutOfOrder extends BusType
  val all = Seq(SingleCyc, MultiCyc, Pipeline, OutOfOrder)
}

object BusConnect {
  def apply[T <: Data](
    src:   DecoupledIO[T],
    dst:   DecoupledIO[T],
    busTp: BusType = SingleCyc
  ) = {
    busTp match {
      case SingleCyc  => { dst <> src }
      case MultiCyc   => { dst <> src }
      case Pipeline   => {
        src.ready := dst.ready
        dst.valid := RegEnable(src.valid, dst.ready)
        dst.bits  := RegEnable(src.bits, dst.ready)
      }
      case OutOfOrder => { dst <> Queue(src, 16) }
    }
  }
}

object RegDstPacket {
  def apply[T <: Data](
    fwdsrc: FwBundle,
    signal: DecodeFoward,
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
