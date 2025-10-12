package NjuProjN2

import chisel3._
import chisel3.util._

class Encoder8to3 extends Module {
  val io = IO(new Bundle{
    val in    = Input(Uint(8.W))
    val out   = Input(Uint(8.W))
  })
}
