package NjuProjN2

import chisel3._
import chisel3.util._

// MSB Prior
class Encoder8to3 extends Module {
  val io = IO(new Bundle{
    val in    = Input(UInt(8.W))
    val out   = Output(UInt(3.W))
    val valid = Output(Bool())
  })

  val cum = Wire(Vec(9, UInt(3.W)))
  val ena = Wire(Vec(9, Bool()))
  cum(8) := 0.U
  ena(8) := 0.U
  for (i <- 7 to 0 by -1) {
    cum(i) := Mux(io.in(i) & (~ena(i+1)), i.U, 0.U) | cum(i+1)
    ena(i) := ena(i+1) | io.in(i)
  }
  io.out := cum(0)
  io.valid := (io.in =/= 0.U(8.W))
}
