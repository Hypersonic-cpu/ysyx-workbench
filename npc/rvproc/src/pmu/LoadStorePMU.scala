package rvproc.pmu

import chisel3._
import chisel3.util._
import chisel3.BlackBox
import chisel3.util.HasBlackBoxPath
import rvproc.Tp
import rvproc.PATH

class LoadStorePMU extends BlackBox with HasBlackBoxPath {
  val io = IO(new Bundle {
    val clock    = Input(Clock())
    val reset    = Input(Reset())
    val trigReq  = Input(Bool())
    val trigResp = Input(Bool())
    val addr     = Input(Tp.AddrType())
    // val isIdle    = Input(Bool())
    // val idleCause = Input(IFIdleCause())
  })
  addPath(PATH.dpic("LoadStorePMU.sv"))
}
