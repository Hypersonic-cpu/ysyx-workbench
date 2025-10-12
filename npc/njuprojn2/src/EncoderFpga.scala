package NjuProjN2

import chisel3._
import chisel3.util._

class EncoderFpga extends Module {
  val io = IO(new Bundle {
    val in    = Input(UInt(8.W))
    val seg7MsbA   = Output(UInt(7.W))
    val valid      = Output(Bool())
  })
  
  val logic = Module(new Encoder8to3())
  val trans = Module(new HexTo7Seg())

  // Inputs
  logic.io.in  := io.in

  // Internal
  trans.io.ena := logic.io.valid 
  trans.io.in  := logic.io.out 

  // Output
  io.seg7MsbA  := trans.io.segMsbA
  io.valid     := logic.io.valid
}
