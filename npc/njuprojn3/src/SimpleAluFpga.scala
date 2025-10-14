package NjuProjN3

import chisel3._
import chisel3.util._
import chisel3.experimental._

class SimpleAluFpga extends Module {
  val io = IO(new Bundle {
    val in1        = Input(UInt(4.W))
    val in2        = Input(UInt(4.W))
    val fn         = Input(UInt(3.W))
    val in1DSgn    = Output(UInt(7.W))
    val in1Disp    = Output(UInt(7.W))
    val in2DSgn    = Output(UInt(7.W))
    val in2Disp    = Output(UInt(7.W))
    val emptyD1    = Output(UInt(7.W))
    val emptyD2    = Output(UInt(7.W))
    val outDSgn    = Output(UInt(7.W))
    val outDisp    = Output(UInt(7.W))
    val out        = Output(UInt(4.W))
    val cflag      = Output(Bool())
    val oflag      = Output(Bool())
    val zflag      = Output(Bool())
  })
  /** Functional Unit */
  val alu = Module(new SimpleAlu())
  alu.io.in1 := io.in1
  alu.io.in2 := io.in2
  alu.io.fn := io.fn
  alu.io.clk := 0.B
  val aluOut = alu.io.out
  io.cflag := alu.io.cflg
  io.oflag := alu.io.oflg
  io.zflag := alu.io.zflg
  io.out   := aluOut
  
  /** Display */
  val enc1 = Module(new HexTo7Seg())
  val enc2 = Module(new HexTo7Seg())
  val enco = Module(new HexTo7Seg())
  val in1v = Mux(io.in1(3), (~io.in1) + 1.U, io.in1)
  val in2v = Mux(io.in2(3), (~io.in2) + 1.U, io.in2)
  val outv = Mux(aluOut(3), (~aluOut) + 1.U, aluOut)
  enc1.io.ena := 1.B
  enc2.io.ena := 1.B
  enco.io.ena := 1.B
  enc1.io.in := in1v
  enc2.io.in := in2v
  enco.io.in := outv
  io.in1Disp := ~enc1.io.segMsbA
  io.in2Disp := ~enc2.io.segMsbA
  io.outDisp := ~enco.io.segMsbA
  io.in1DSgn := ~(0b0.U(6.W) ## io.in1(3))
  io.in2DSgn := ~(0b0.U(6.W) ## io.in2(3))
  io.outDSgn := ~(0b0.U(6.W) ## aluOut(3))

  io.emptyD1 := 0b111_1111.U(7.W)
  io.emptyD2 := 0b111_1111.U(7.W)
}
