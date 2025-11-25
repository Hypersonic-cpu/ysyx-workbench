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

object BusConnect{
  def apply[T <: Data](lhs: DecoupledIO[T], rhs: DecoupledIO[T], busTp: BusType = SingleCyc) = {
    // val arch: BusType = SingleCyc
    busTp match {
      case SingleCyc  => { rhs.bits := lhs.bits }
      case MultiCyc   => { rhs <> lhs }
      case Pipeline   => { rhs <> RegEnable(lhs, lhs.fire) }
      case OutOfOrder => { rhs <> Queue(lhs, 16) }
    }
  }
}
