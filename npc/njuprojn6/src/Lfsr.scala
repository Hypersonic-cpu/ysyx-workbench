package NjuProjN6

import chisel3._
import chisel3.util._

class Lfsr extends Module {
  val io = IO(new Bundle{
    val load  = Input(Bool())
    val ldVal = Input(UInt(8.W))
    val out   = Output(UInt(8.W))
  })

  val reg = RegInit(1.U(8.W))
  val feedback = reg(0) ^ reg(2) ^ reg(3) ^ reg(4)
  val tmp = Mux(io.load, io.ldVal, feedback ## reg(7, 1))
  // NOTE: Auto reset to 1 if all bits are zero (off-cycle)
  reg := Mux(tmp.orR, tmp, 1.U(8.W))
  io.out := reg
}
