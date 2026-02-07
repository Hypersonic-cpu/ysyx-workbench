package rvproc.device

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxPath
import rvproc.axi4.AXIBus

class AXIConnBox extends Module {
  val io = IO(new Bundle {
    val master = Flipped(new AXIBus)
    val flush  = Input(new Bundle{
      val id = UInt(16.W)
      val valid = Bool()
    })
  })
  io := DontCare
  dontTouch(io)
  dontTouch(clock)
  dontTouch(reset)
}

