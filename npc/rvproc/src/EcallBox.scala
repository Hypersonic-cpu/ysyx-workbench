package rvproc

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxPath

class EcallBox extends BlackBox with HasBlackBoxPath {
  val io = IO(new Bundle {
    val clock    = Input(Clock())
    val reset    = Input(Reset())
    val isEcall  = Input(Bool())
    val isEbreak = Input(Bool())
    val pcin     = Input(Tp.RegType())
    val a0in     = Input(Tp.RegType())
    val a5in     = Input(Tp.RegType())
  })

  if (GlbCtrl.debug) {
    addPath(PATH.dpic("EcallBox.sv"))
  } else {
    addPath(PATH.dpic("FakeEcallBox.sv"))
  }
}
