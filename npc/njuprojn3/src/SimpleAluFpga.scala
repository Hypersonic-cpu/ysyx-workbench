package NjuProjN3

import chisel3._
import chisel3.util._
import chisel3.experimental._

class SimpleAluFpga extends Module {
  val io = IO(new Bundle {
    val in    = Input(UInt(8.W))
    val seg7MsbA   = Output(UInt(7.W))
    val out        = Output(UInt(3.W))
    val valid      = Output(Bool())
  })
  
}
