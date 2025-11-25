package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert

import BitMath._

// NOTE: CSR should be placed in rs2V
class EXU extends Module {
  val io = IO(new Bundle {
    val rs1V  = Input(Tp.RegType())
    val rs2V  = Input(Tp.RegType())
    val pc    = Input(Tp.RegType())
    val imm   = Input(Tp.RegType())
    val op    = Input(AluOp())
    val sel   = Input(new AluSel)
    val brJmp = Input(new BrJmp)
    val res   = Output(Tp.RegType())
    val takeBr = Output(Bool())
  })

  // Cmp: Always compare rs1V and rs2V
  val cmpe = (io.op === AluOp.Sltu || io.op === AluOp.Slt)
  val cmpu = io.op === AluOp.Sltu

  val cmp1s = io.rs1V
  val cmp2s = ~io.rs2V
  val cmpSum = 1.U + cmp1s.UExt() + cmp2s.UExt()
  val cmpOF = (
    ~(cmp1s.MSB() ^ cmp2s.MSB())) & 
     (cmp1s.MSB() ^ cmpSum.MSB())
  val cmpLT = Mux(cmpu, 
    ~cmpSum.MSB(-1), cmpSum.MSB() ^ cmpOF).asBool
  val cmpEQ = ~cmpSum(ISA.RegBits-1, 0).orR.asBool

  val b = io.brJmp
  io.takeBr := 
    (cmpLT && b.bIflt) || (~cmpLT && b.bIfge) ||
    (cmpEQ && b.bIfeq) || (~cmpEQ && b.bIfne)

  // io.brCmp.blt := cmpLT
  // io.brCmp.beq := ~cmpSum(ISA.RegBits-1, 0).orR

  printf(
    cf"\tsrc1 Rsel${~io.sel.rs1SelPC} Inv${io.sel.rs1Invert} = ${src1}%x, : src2 Rsel${~io.sel.rs2SelImm} Inv${io.sel.rs2Invert} = ${src2}%x : Imm = ${io.imm}%x\n")

  val flip1 = io.sel.rs1Invert
  val flip2 = io.sel.rs2Invert && (io.op =/= AluOp.Srr)
  val raw1 = Mux(io.sel.rs1SelPC, io.pc, io.rs1V)
  val raw2 = Mux(io.sel.rs2SelImm, io.imm, io.rs2V)
  val src1 = Mux(flip1, ~raw1, raw1)
  val src2 = Mux(flip2, ~raw2, raw2)
  printf(cf"\tsrc1${src1}%x : src2${src2}%x\n")

  // Compute Add for op = Sltu, Slt
  // Since B-Type uses address
  val sums = src1 + src2 + Mux(flip2, 1.U, 0.U)
  val aout = MuxCase(sums, Seq(
    (io.op === AluOp.Sll) -> (src1 << src2(4, 0)),
    (io.op === AluOp.Srr) -> Mux(io.sel.rs2Invert,
      (src1.asSInt >> src2(4, 0)).asUInt, src1 >> src2(4, 0)),
    (io.op === AluOp.And) -> (src1 & src2),
    (io.op === AluOp.Or ) -> (src1 | src2),
    (io.op === AluOp.Xor) -> (src1 ^ src2),
  ))

  io.res := Mux(io.sel.saveCmp,
    /* SLT, SLTU */ cmpLT.asUInt,
    aout
  )

  // printf(cf"\t${src1}%x op ${src2}%x = o${over} c${ansc}%x ${anst}%x\n")
  // when (io.sel.isBranch || io.op === AluOp.Slt || io.op === AluOp.Sltu) {
  //   printf(cf"Cmp: src1 ${src1}%x, src2 ${src2}%x, "
  //     + cf"ansc ${ansc}%x OF${over} LT${less} EQ${io.brCmp.beq}\n")
  // }
}

class ExecuteStage extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new DecodeToExecute))
    val out = Decoupled(new ExecuteToMemory)
  })
  io.in.ready  := true.B
  io.out.valid := true.B

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

  iExe.io.brJmp := ioid.brJmp

  iols.aluOut  := iExe.io.res
  iols.takeBr  := iExe.io.takeBr

  iols.memOp   := ioid.memOp
  iols.rs2Val  := ioid.rs2V

  // Foward only
  ioid.foward <> iols.foward
}
