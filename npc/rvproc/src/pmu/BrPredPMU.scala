package rvproc.pmu

import chisel3._
import chisel3.util._
import chisel3.BlackBox
import chisel3.util.HasBlackBoxPath
import rvproc._

class BrPredPMU extends BlackBox with HasBlackBoxPath {
  val io = IO(new Bundle {
    val clock        = Input(Clock())
    val reset        = Input(Reset())
    val valid        = Input(Bool())
    val predTaken    = Input(Bool())
    val actualTaken  = Input(Bool())
    val predTarget   = Input(Tp.AddrType())
    val actualTarget = Input(Tp.AddrType())
    val btbHit       = Input(Bool())
    val brPC         = Input(Tp.AddrType())
  })
  addPath(PATH.dpic("BrPredPMU.sv"))
}
