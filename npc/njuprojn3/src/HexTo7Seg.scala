package NjuProjN3

import chisel3._
import chisel3.util._

class HexTo7Seg extends Module {
  val io = IO(new Bundle{
    val in = Input(UInt(4.W))
    val ena = Input(Bool())
    val segMsbA = Output(UInt(7.W))
  })

  io.segMsbA := Mux(io.ena, 0x7f.U(7.W), 0x0.U(7.W)) &
    MuxLookup(io.in, 0.U) (
      Seq(
        0.U(4.W)  -> (0b1111110).U(7.W),
        1.U(4.W)  -> (0b0110000).U(7.W),
        2.U(4.W)  -> (0b1101101).U(7.W),
        3.U(4.W)  -> (0b1111001).U(7.W),
        4.U(4.W)  -> (0b0110011).U(7.W),
        5.U(4.W)  -> (0b1011011).U(7.W),
        6.U(4.W)  -> (0b1011111).U(7.W),
        7.U(4.W)  -> (0b1110000).U(7.W),
        8.U(4.W)  -> (0b1111111).U(7.W),
        9.U(4.W)  -> (0b1111011).U(7.W),
        10.U(4.W) -> (0b1110111).U(7.W),
        11.U(4.W) -> (0b0011111).U(7.W),
        12.U(4.W) -> (0b1001110).U(7.W),
        13.U(4.W) -> (0b0111101).U(7.W),
        14.U(4.W) -> (0b1001111).U(7.W),
        15.U(4.W) -> (0b1000111).U(7.W)
      )
    )
}
