package NjuProjN3

import chisel3._
import chisel3.util._


object Cmd extends ChiselEnum {
  val Add, Sub, Not, And, Or, Xor, Lt, Eq = Value
}

class SimpleAlu extends Module {
  val io = IO(new Bundle{
    val fn    = Input(UInt(3.W))
    // Already the 2's Complement
    val in1   = Input(UInt(4.W))
    val in2   = Input(UInt(4.W))
    val out   = Output(UInt(4.W))
    val cflg  = Output(Bool())
    val oflg  = Output(Bool())
    val zflg  = Output(Bool())
  })

  val aluOp = Cmd(io.fn)

  val in1Op = io.in1 
  val in2Op = io.in2 ^ Mux(
    aluOp === Cmd.Sub || aluOp === Cmd.Lt || aluOp === Cmd.Eq, 
    0b1111.U(4.W), 0.U(4.W)
  )

  // Add-Sub
  val sumAll  = in1Op.pad(5) + in2Op.pad(5)
  val sumRes  = sumAll(3, 0)
  val sumCflg = sumAll(4)
  val sumZflg = ~sumCflg.orR
  val sumOflg = (~(in1Op(3) ^ in2Op(3))) & (sumRes(3) ^ in1Op(3))

  // Comp
  val ltRes = sumRes(3).pad(4)
  val eqRes = sumZflg.pad(4)

  // Logical
  val andRes = in1Op & in2Op
  val orRes  = in1Op | in2Op
  val notRes = ~ in1Op
  val xorRes = in1Op ^ in2Op

  val isArith = aluOp === Cmd.Add || aluOp === Cmd.Sub
  io.out  := Mux1H( Seq(
    (isArith) -> sumRes,
    (aluOp === Cmd.Not ) -> notRes,
    (aluOp === Cmd.And ) -> andRes,
    (aluOp === Cmd.Or  ) -> orRes,
    (aluOp === Cmd.Xor ) -> xorRes,
    (aluOp === Cmd.Lt  ) -> ltRes,
    (aluOp === Cmd.Eq  ) -> eqRes
    ))
  io.cflg := Mux(isArith, sumCflg.asBool, 0.B)
  io.oflg := Mux(isArith, sumOflg.asBool, 0.B)
  io.zflg := Mux(isArith, sumZflg.asBool, 0.B)
}
