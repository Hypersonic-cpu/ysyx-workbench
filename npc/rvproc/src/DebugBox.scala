package rvproc

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxPath

class DebugBox extends BlackBox with HasBlackBoxPath {
  val io = IO(new Bundle {
    val clock    = Input(Clock())
    val reset    = Input(Reset())
    val probePin = Output(Tp.RegIdxType())
    val probeOut = Input(Tp.RegType())
    val probePC  = Input(Tp.PCType())
  })
  addPath(PATH.dpic("DebugBox.sv"))
}
