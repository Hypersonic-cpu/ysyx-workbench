package rvproc.pmu

import chisel3._
import chisel3.util._
import chisel3.BlackBox
import chisel3.util.HasBlackBoxPath
import rvproc._

class WrBackPMU extends BlackBox with HasBlackBoxPath {
  val io = IO(new Bundle {
    val clock     = Input(Clock())
    val reset     = Input(Reset())
    val isNewInst = Input(Bool())
    val pc        = Input(Tp.AddrType())
    val inst      = Input(UInt((if (GlbCtrl.debug) 32 else 0).W))
    val stallTp   = Input(UInt(8.W))
  })
  addPath(PATH.dpic("WrBackPMU.sv"))
}
