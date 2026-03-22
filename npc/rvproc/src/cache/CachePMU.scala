package rvproc.pmu

import chisel3._
import chisel3.util._
import chisel3.assert.Assert
import rvproc.axi4._
import rvproc.BusType._
import rvproc.BusConnect
import rvproc.BitMath._
import rvproc.Tp
import rvproc.ISA
import rvproc.axi4.AXI.RespStatus.OKAY
import rvproc.axi4.AXI.BurstOpts._
import rvproc.GlbCtrl.{debug, sta}
import rvproc.PATH

class CacheHwPMU extends Module {
  val io         = IO(new Bundle {
    val access = Input(Bool())
    val hit    = Input(Bool())
    val regidx = Input(UInt(2.W))
    val regout = Output(UInt(32.W))
  })
  val accCountHi = RegInit(0.U(32.W))
  val accCountLo = RegInit(0.U(32.W))
  val hitCountHi = RegInit(0.U(32.W))
  val hitCountLo = RegInit(0.U(32.W))
  when(io.access) {
    accCountLo := accCountLo + 1.U
    accCountHi := accCountHi + Mux(accCountLo.andR, 1.U, 0.U)
  }
  when(io.hit) {
    hitCountLo := hitCountLo + 1.U
    hitCountHi := hitCountHi + Mux(accCountLo.andR, 1.U, 0.U)
  }
  assert(io.hit Implies io.access, "Hit but not access ?");
  io.regout := MuxLookup(io.regidx, 0.U)(
    Seq(
      0.U -> accCountLo,
      1.U -> accCountHi,
      2.U -> hitCountLo,
      3.U -> hitCountHi
    )
  )
}

class CacheSwPMU extends BlackBox with HasBlackBoxPath {
  val io = IO(new Bundle {
    val clock    = Input(Clock())
    val reset    = Input(Reset())
    val resp     = Input(Bool())
    val respHit  = Input(Bool())
    val respAddr = Input(Tp.AddrType())
    val req      = Input(Bool())
    val reqAddr  = Input(Tp.AddrType())
    val id       = Input(UInt(16.W))
  })
  addPath(PATH.dpic("CacheSwPMU.sv"))
}

class PfSwPMU extends BlackBox with HasBlackBoxPath {
  val io = IO(new Bundle {
    val clock      = Input(Clock())
    val reset      = Input(Reset())
    val pfIssued   = Input(Bool())
    val pfHitC2    = Input(Bool())
    val pfUseful   = Input(Bool())
    val pfAddr     = Input(Tp.AddrType())
  })
  addPath(PATH.dpic("PfSwPMU.sv"))
}
