package rvProc

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxPath

class PMemBox extends BlackBox with HasBlackBoxPath {
  val io = IO(new Bundle {
    val clock    = Input(Clock())
    val reset    = Input(Reset())
    val pcin     = Input(Tp.PCType())
    val addr     = Input(Tp.AddrType())
    val data     = Input(Tp.RegType())
    val memEn    = Input(Bool())
    val wrEn     = Input(Bool())
    val byteMask = Input(UInt(8.W))      // although UInt4 is enough
    val loadRaw  = Output(Tp.RegType())  // NOTE: always with the same length
    val instRaw  = Output(Tp.InstType())
  })

  addPath(PATH.dpic("PMemBox.sv"))
}
