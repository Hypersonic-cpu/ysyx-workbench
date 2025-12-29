package rvproc.pmu

import chisel3._
import chisel3.util._
import chisel3.BlackBox
import chisel3.util.HasBlackBoxPath
import rvproc._

class DecodePMU extends BlackBox with HasBlackBoxPath {
  val io = IO(new Bundle{
    val isNewInst = Input(Bool())
    val instType  = Input(ITYPE())
    val instOp    = Input(InstOp())
  })
  addPath(PATH.dpic("DecodePMU.sv"))
}
