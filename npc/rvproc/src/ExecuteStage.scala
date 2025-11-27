package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert

import BitMath._

class EXU extends Module {
  val io = IO(new Bundle {
    val rs1V  = Input(Tp.RegType())
    val rs2V  = Input(Tp.RegType())
    val pc    = Input(Tp.RegType())
    val imm   = Input(Tp.RegType())
    val op    = Input(AluOp())
    val sel   = Input(new AluSel)
    val aluOut = Output(Tp.RegType())
  })

  // Cmp: Always compare rs1V and rs2V

  // val cmp1s = io.rs1V
  // val cmp2s = ~Mux(io.sel.cmpImm, io.imm, io.rs2V)
  // val cmpSum = 1.U + cmp1s.UExt() + cmp2s.UExt()

  val flip1 = io.sel.rs1Invert
  val flip2 = io.sel.rs2Invert && (io.op =/= AluOp.Srr)
  val raw1 = Mux(io.sel.rs1SelPC, io.pc, io.rs1V)
  val raw2 = Mux(io.sel.rs2SelImm, io.imm, io.rs2V)
  val src1 = Mux(flip1, ~raw1, raw1)
  val src2 = Mux(flip2, ~raw2, raw2)

  printf(
    cf"[ ${io.pc}%x EX ] "
    +cf"src1 selR${~io.sel.rs1SelPC} Inv${io.sel.rs1Invert} = ${src1}%x, "
    +cf"src2 selR${~io.sel.rs2SelImm} Inv${io.sel.rs2Invert} = ${src2}%x,"
    +cf" Imm = ${io.imm}%x" 
    // +cf" cmp(<,=) (${cmpLT},${cmpEQ}), jmp(<,>=,=,!=) (${b.bIflt},${b.bIfge},${b.bIfeq},${b.bIfne})"
    + "\n")

  val cmpEn = 
    io.op === AluOp.Sltu || io.op === AluOp.Slt
  val cmpU = io.op === AluOp.Sltu
  // p->q <=> ~p or q
  assert(~cmpEn || (~flip1 && flip2))
  val esum = 
    src1.UExt() + src2.UExt() + Mux(flip2, 1.U, 0.U)
  // Corner case: INT_MIN
  // pos+neg will not cause overflow
  // same sign addition not altering sign
  val cmpOF = 
    ~(src1.MSB() ^ src2.MSB()) &
     (src1.MSB() ^ esum.MSB())

  val cmpLT = Mux(cmpU, 
    ~esum.MSB(-1), esum.MSB() ^ cmpOF).asBool
  val aout = MuxCase(esum, Seq(
    (io.op === AluOp.Sll) -> (src1 << src2(4, 0)),
    (io.op === AluOp.Srr) -> Mux(io.sel.rs2Invert,
      (src1.asSInt >> src2(4, 0)).asUInt,
      src1 >> src2(4, 0)
    ),
    (io.op === AluOp.And) -> (src1 & src2),
    (io.op === AluOp.Or ) -> (src1 | src2),
    (io.op === AluOp.Xor) -> (src1 ^ src2),
  ))

  io.aluOut := Mux(cmpEn, cmpLT.asUInt, aout)
}

class ExecuteStage extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new DecodeToExecute))
    val out = Decoupled(new ExecuteToMemory)
    val toFetch = Decoupled(new ExecuteBackward)
  })
  io.in.ready  := true.B
  io.out.valid := true.B
  io.toFetch.valid := true.B

  val iExe = Module(new EXU)
  val ioid = io.in.bits
  val iols = io.out.bits
  io.in.ready  := true.B
  io.out.valid := true.B
  iExe.io.op   := ioid.aluOp
  iExe.io.sel  := ioid.aluSel
  iExe.io.rs1V := ioid.rs1V
  iExe.io.rs2V := ioid.rs2V
  iExe.io.imm  := ioid.imm
  iExe.io.pc   := ioid.foward.pc

  /** NOTE: Back to Fetch */
  val iobk = io.toFetch.bits
  iobk.brAbs := ioid.brAbs
  iobk.brVal := iExe.io.aluOut

  /** NOTE: To LSU, AluOut = Addr */
  iols.aluOut  := iExe.io.aluOut
  iols.memOp   := ioid.memOp
  iols.rs2Val  := ioid.rs2V

  /** NOTE: Foward */
  ioid.foward <> iols.foward

  /** NOTE: Interrupt */
  val iInt = Module(new EcallBox)
  iInt.io.clock := clock
  iInt.io.reset := reset
  iInt.io.a0in  := ioid.rs1V
  iInt.io.pcin  := ioid.foward.pc
  iInt.io.isEbreak := ioid.foward.ebreak
  iInt.io.isEcall  := ioid.foward.ecall 
}
