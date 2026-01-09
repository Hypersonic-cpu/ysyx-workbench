package rvproc.pmu

import chisel3._
import chisel3.util._
import chisel3.BlackBox
import chisel3.util.HasBlackBoxPath
import rvproc.Tp
import rvproc.PATH

object IFIdleCause extends ChiselEnum {
  val Calc, Memory, BrPred, DataHaz, NotReady, Reserved = Value
}

class FetchPMU extends BlackBox with HasBlackBoxPath {
  val io = IO(new Bundle {
    val clock     = Input(Clock())
    val reset     = Input(Reset())
    val trigFetch = Input(Bool())
    val trigIssue = Input(Bool())
    val pc        = Input(Tp.AddrType())
    val inst      = Input(Tp.RegType())
    // val isIdle    = Input(Bool())
    // val idleCause = Input(IFIdleCause())
  })
  addPath(PATH.dpic("FetchPMU.sv"))
}
