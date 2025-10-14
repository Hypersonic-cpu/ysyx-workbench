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
    val clk   = Input(Clock())
  })

  val aluOp = Cmd(io.fn)
  val in1Op = io.in1 
  val needFlip = aluOp === Cmd.Sub || aluOp === Cmd.Lt || aluOp === Cmd.Eq
  val in2Op = io.in2 ^ Mux(needFlip, 0b1111.U(4.W), 0.U(4.W))
  
  // Add-Sub
  val sumAll  = in1Op.pad(5) + in2Op.pad(5) + Mux(needFlip, 0b1.U(5.W), 0.U(5.W))
  val sumRes  = sumAll(3, 0)
  val sumCflg = sumAll(4)
  val sumZflg = ~sumRes.orR
  // NOTE:  0b1111 and 0b1000
  //         =  -1  -  -8
  // Invert 0b1111 and 0b1000
  //         =  -1  +   8U (Overflow for signed)
  // Thus we add the 'one' in sumRes but not in2Op
  val sumOflg = (~(in1Op(3) ^ in2Op(3))) & (sumRes(3) ^ in1Op(3))

  // Comp
  val ltRes = (sumRes(3, 3) ^ sumOflg(0, 0)).pad(4)
  val eqRes = sumZflg(0, 0).pad(4)

  // Logical
  val andRes = in1Op & in2Op
  val orRes  = in1Op | in2Op
  val notRes = ~ in1Op
  val xorRes = in1Op ^ in2Op

  // withClock(io.clk) {
  //   printf(cf"Debug: ${io.in1} ${io.in2} (sum $sumAll cf $sumCflg lt $ltRes)" +
  //     cf"=> SubCarryout ${(~ltRes(0)).asBool}\n")
  // }
  

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
  io.cflg := Mux1H(Seq(
    (aluOp === Cmd.Add) -> sumCflg.asBool,
    (aluOp === Cmd.Sub) -> (~ltRes(0)).asBool,
    (~isArith) -> 0.B
  ))
  io.oflg := Mux(isArith, sumOflg.asBool, 0.B)
  io.zflg := Mux(isArith, sumZflg.asBool, 0.B)
}
