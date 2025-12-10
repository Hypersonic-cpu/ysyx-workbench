package rvproc.axi4

import rvproc._

import chisel3._
import chisel3.util._
import java.nio.BufferUnderflowException
import rvproc.PortPassing.DriveDir

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
