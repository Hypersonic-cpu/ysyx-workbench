package rvproc

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxPath

// PMemBox : Single memory port
class PMemBox extends Module { // BlackBox with HasBlackBoxPath {
  val io = IO(new Bundle {
    val clock    = Input(Clock())
    val reset    = Input(Reset())
    val addr     = Input(Tp.AddrType())
    val data     = Input(Tp.RegType())
    val memEn    = Input(Bool())
    val wrEn     = Input(Bool())
    val byteMask = Input(UInt(8.W))      // although UInt4 is enough
    val loadRaw  = Output(Tp.RegType())  // NOTE: always with the same length
  })

  val fakeMem = Mem(512, UInt(32.W))
  io.loadRaw := 0.U
  when (io.memEn) {
    when (io.wrEn) {
      fakeMem.write(io.addr(10, 2), io.data & io.byteMask) // BUG:
    }.otherwise {
      io.loadRaw := fakeMem.read(io.addr(10, 2))
    }
  }
  // addPath(PATH.dpic("PMemBox.sv"))
}
