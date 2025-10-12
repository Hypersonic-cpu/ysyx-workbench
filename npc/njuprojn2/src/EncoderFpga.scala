package NjuProjN2

import chisel3._
import chisel3.util._

class EncoderFpga extends Module {
  val io = IO(new Bundle {
    val in    = Input(UInt(8.W))
    val seg7MsbA   = Output(UInt(7.W))
    val valid      = Output(Bool())
  })
  
  val logic = new Encoder8to3()
  val trans = new HexTo7Seg()
  io.valid := logic.io.valid
  io.in    := logic.io.in
  trans.io.in  := logic.io.out 
  trans.io.ena := logic.io.valid 
  io.seg7MsbA := trans.io.segMsbA
}
