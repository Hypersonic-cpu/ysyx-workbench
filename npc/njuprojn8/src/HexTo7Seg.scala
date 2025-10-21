package NjuProjN8

import chisel3._
import chisel3.util._

class HexTo7Seg extends Module {
  val io = IO(new Bundle{
    val in = Input(UInt(4.W))
    val ena = Input(Bool())
    val segMsbA = Output(UInt(8.W))
  })

  io.segMsbA := Mux(io.ena, 0xff.U(8.W), 0x0.U(8.W)) &
    MuxLookup(io.in, 0.U) (
      Seq(
        0.U(4.W)  -> (0b11111100).U(8.W),
        1.U(4.W)  -> (0b01100000).U(8.W),
        2.U(4.W)  -> (0b11011010).U(8.W),
        3.U(4.W)  -> (0b11110010).U(8.W),
        4.U(4.W)  -> (0b01100110).U(8.W),
        5.U(4.W)  -> (0b10110110).U(8.W),
        6.U(4.W)  -> (0b10111110).U(8.W),
        7.U(4.W)  -> (0b11100000).U(8.W),
        8.U(4.W)  -> (0b11111110).U(8.W),
        9.U(4.W)  -> (0b11110110).U(8.W),
        10.U(4.W) -> (0b11101110).U(8.W),
        11.U(4.W) -> (0b00111110).U(8.W),
        12.U(4.W) -> (0b10011100).U(8.W),
        13.U(4.W) -> (0b01111010).U(8.W),
        14.U(4.W) -> (0b10011110).U(8.W),
        15.U(4.W) -> (0b10001110).U(8.W)
      )
    )
}
