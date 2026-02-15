package rvproc

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxPath
import rvproc.axi4.AXIBus

class PMemBox extends Module {
  val io = IO(new Bundle {
    val master = Flipped(new AXIBus)
  })
  // val fakeReg = Reg(Flipped(new AXIBus))
  // fakeReg := io.master;
  io := DontCare
  dontTouch(io)
  dontTouch(clock)
  dontTouch(reset)
}

