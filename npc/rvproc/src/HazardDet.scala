package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert
import rvproc.axi4._
import rvproc.axi4.AXI.RespStatus._
import rvproc.axi4.AXI.BurstOpts._
import rvproc.pmu.FetchPMU
import BitMath._

class HazardDet extends Module {
  val io = IO(new Bundle {})
}

class RAWDet extends Module {
  // TODO: CSR
  val io = IO(new Bundle {
    val raw    = Output(Bool())
    val decode = Flipped(Decoupled(new DecodeHazard))
    val exsrd  = Flipped(Decoupled(Tp.RegIdxType()))
    val lssrd  = Flipped(Decoupled(Tp.RegIdxType()))
    val wbsrd  = Flipped(Decoupled(Tp.RegIdxType()))
  })

  io.decode.ready := true.B
  io.exsrd.ready  := true.B
  io.lssrd.ready  := true.B
  io.wbsrd.ready  := true.B

  def conflictWith[T <: DecoupledIO[Data]](
    self:  DecoupledIO[DecodeHazard],
    other: T
  ) = {
    other.valid && self.valid &&
    (other.bits === self.bits.rs1 || other.bits === self.bits.rs2)
  }
  io.raw :=
    conflictWith(
      io.decode,
      io.exsrd
    ) || conflictWith(
      io.decode,
      io.lssrd
    ) || conflictWith(io.decode, io.wbsrd)
}
