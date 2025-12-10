package rvproc

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxPath
import rvproc.axi4.AXILite

class PMemBox extends Module {
  val io = IO(Flipped(new AXILite))
  io := DontCare
  dontTouch(io)
}

