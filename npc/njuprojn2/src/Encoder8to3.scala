package NjuProjN2

import chisel3._
import chisel3.util._

class Encoder8to3 extends Module {
  val io = IO(new Bundle{
    val in    = Input(UInt(8.W))
    val out   = Output(UInt(3.W))
  })

  val cum = Wire(Vec(8, UInt(3.W)))
  cum(0) := 0.U
  for (i <- 1 until 8) {
    cum(i) := Mux(io.in(i), i.U, 0.U) | cum(i-1)
  }
  io.out := cum(7)
}
