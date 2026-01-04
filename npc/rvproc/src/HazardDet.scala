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

class RdPair extends Bundle {
  val gprWE = Bool()
  val gprRd = Tp.RegIdxType()
  val csrWE = Bool()
  val csrRd = Tp.CsrIdxType()
}

class DecodeHazard extends Bundle {
  val rs1  = Tp.RegIdxType()
  val rs2  = Tp.RegIdxType()
  val csr  = Tp.CsrIdxType()
  val use1 = Bool()
  val use2 = Bool()
  val useC = Bool()
}

class RAWDet extends Module {
  // TODO: CSR
  val io = IO(new Bundle {
    val raw    = Output(Bool())
    val decode = Flipped(Decoupled(new DecodeHazard))
    val exsrd  = Flipped(Decoupled(new RdPair))
    val lssrd  = Flipped(Decoupled(new RdPair))
    val wbsrd  = Flipped(Decoupled(new RdPair))
  })

  io.decode.ready := true.B
  io.exsrd.ready  := true.B
  io.lssrd.ready  := true.B
  io.wbsrd.ready  := true.B

  def conflictWith(
    self:  DecoupledIO[DecodeHazard],
    other: DecoupledIO[RdPair]
  ) = {
    val cflGpr = other.bits.gprWE && (
      (self.bits.use1 && other.bits.gprRd === self.bits.rs1) ||
        (self.bits.use2 && other.bits.gprRd === self.bits.rs2)
    )
    val cflCsr = other.bits.csrWE && (
      self.bits.useC && other.bits.csrRd === self.bits.csr
    )

    other.valid && self.valid && (cflGpr || cflCsr)
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
