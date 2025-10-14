package NjuProjN6

import chisel3._
import chisel3.Bundle

class LfsrFpga extends Module {
  val io = IO(new Bundle {
    val load  = Input(Bool())
    val ldVal = Input(UInt(8.W))
    val segs  = Output(Vec(2, UInt(8.W)))
    val leds  = Output(UInt(8.W))
  })
  
  val lfsr = Module(new Lfsr())
  val disp = Vec(2, new HexTo7Seg().io)

  disp(0).ena := 1.B
  disp(1).ena := 1.B
  disp(0).in  := lfsr.io.out(3, 0)
  disp(1).in  := lfsr.io.out(7, 4)
  io.segs(1) := disp(0).segMsbA
  io.segs(0) := disp(1).segMsbA

  lfsr.io.load := io.load
  lfsr.io.ldVal := io.ldVal

  io.leds := lfsr.io.out
}
