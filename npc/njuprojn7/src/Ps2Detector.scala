package NjuProjN7

import chisel3._
import chisel3.util._

class Ps2Detector extends Module {
  val io = IO(new Bundle{
    val ps2Clk = Input(Bool())
    val ps2Dat = Input(Bool())
  })

  // val ps2Recv = R
}
