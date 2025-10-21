package NjuProjN7

import chisel3._ 
import chisel3.util._

class KeyToASCII extends Module {
  val io = IO(new Bundle {
    val keycode = Input(UInt(8.W))
    val ascii = Output(UInt(8.W))
    val valid = Output(Bool())
  })

  val keycodeToAscii: Seq[(Int, Int)] = Seq(
    // Main keyboard
    0x45 -> '0', 0x16 -> '1', 0x1E -> '2', 0x26 -> '3', 0x25 -> '4',
    0x2E -> '5', 0x36 -> '6', 0x3D -> '7', 0x3E -> '8', 0x46 -> '9',
    
    0x1C -> 'A', 0x32 -> 'B', 0x21 -> 'C', 0x23 -> 'D', 0x24 -> 'E',
    0x2B -> 'F', 0x34 -> 'G', 0x33 -> 'H', 0x43 -> 'I', 0x3B -> 'J',
    0x42 -> 'K', 0x4B -> 'L', 0x3A -> 'M', 0x31 -> 'N', 0x44 -> 'O',
    0x4D -> 'P', 0x15 -> 'Q', 0x2D -> 'R', 0x1B -> 'S', 0x2C -> 'T',
    0x3C -> 'U', 0x2A -> 'V', 0x1D -> 'W', 0x22 -> 'X', 0x35 -> 'Y',
    0x1A -> 'Z',

    0x0E -> '`',  0x4E -> '-',  0x55 -> '=',  0x5D -> '\\',
    0x54 -> '[',  0x5B -> ']',  0x4C -> ';',  0x52 -> '\'',
    0x41 -> ',',  0x49 -> '.',  0x4A -> '/',
    0x29 -> ' ',  0x66 -> '\b', 0x0D -> '\t', 0x5A -> '\r', 0x76 -> 27,
    
    // Extra
    0x70 -> '0', 0x69 -> '1', 0x72 -> '2', 0x7A -> '3', 0x6B -> '4',
    0x73 -> '5', 0x74 -> '6', 0x6C -> '7', 0x75 -> '8', 0x7D -> '9',
    0x7C -> '*', 0x79 -> '+', 0x7B -> '-', 0x71 -> '.'
  )

  // 0 for invalid inputs
  val romData: Seq[UInt] = (0 until 256).map { addr =>
    keycodeToAscii
      .find(_._1 == addr)
      .map(_._2.toInt.U(8.W))
      .getOrElse(0.U(8.W))
  }

  val rom = VecInit(romData)

  io.ascii := rom(io.keycode)
  io.valid := rom(io.keycode) =/= 0.U
}
