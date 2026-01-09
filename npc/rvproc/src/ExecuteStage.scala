package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert

import BitMath._

class EXU extends Module {
  val io = IO(new Bundle {
    val aluEn = Input(Bool())
    val rs1V  = Input(Tp.RegType())
    val rs2V  = Input(Tp.RegType())
    val csrV  = Input(Tp.RegType())
    val pc    = Input(Tp.RegType())
    val imm   = Input(Tp.RegType())
    val op    = Input(AluOp())
    val br    = Input(new BrInst())
    val sel   = Input(new AluSel)

    val aluOut = Output(Tp.RegType())

    val brRel = Output(Bool())
    val brAbs = Output(Bool())
    val brDel = Output(Tp.RegType())
    val brVal = Output(Tp.RegType())
  })

  val cmpSlt =
    io.op === AluOp.Sltu || io.op === AluOp.Slt
  // val cmpEn  = cmpSlt || io.br.isBr
  // val cmpU   = io.sel.cmpUsgn
  // Mux(io.br.isBr, io.br.bUsgn, io.op === AluOp.Sltu)

  val flip1 = io.sel.rs1Invert
  val flip2 = io.sel.rs2Invert
  // val flip2 = cmpEn || (io.sel.rs2Invert && (io.op =/= AluOp.Srr))
  val raw1  = Mux(io.sel.rs1SelPC, io.pc, io.rs1V)
  val raw2  = Mux(io.sel.rs2SelImm, io.imm, io.rs2V)
  val src1  = Mux(flip1, ~raw1, raw1)
  val src2  = Mux(flip2, ~raw2, raw2)

  // val adder = Module(new CLAdder(32))
  // adder.io.in1 := src1.UExt()
  // adder.io.in2 := src2.UExt()
  // adder.io.cin := flip2
  // val esum = adder.io.out
  val esum = src1.UExt() + src2.UExt() + flip2.asUInt

  // Corner case: INT_MIN
  // pos+neg will not cause overflow
  // same sign addition not altering sign
  // val cmpOF  =
  //   ~(src1.MSB() ^ src2.MSB()) &
  //     (src1.MSB() ^ esum.MSB())
  // val cmpLTS = (esum.MSB() ^ cmpOF).asBool
  val cmpLTS =
    Mux(
      src1.MSB() =/= src2.MSB(),
      esum.MSB(),
      src1.MSB()
    ).asBool
  val cmpLTU = ~esum.MSB(-1).asBool
  val cmpLT  = Mux(io.sel.cmpUsgn, cmpLTU, cmpLTS)
  // Not used by SLT so we directly use rs1V/2V
  val cmpEQ  = io.rs1V === io.rs2V

  val aout = MuxCase(
    esum,
    Seq(
      (io.op === AluOp.Sll) -> (src1 << src2(4, 0)),
      (io.op === AluOp.Srr) -> Mux(
        // io.sel.rs2Invert,
        io.sel.shArith,
        (src1.asSInt >> src2(4, 0)).asUInt,
        src1 >> src2(4, 0)
      ),
      (io.op === AluOp.And) -> (src1 & src2),
      (io.op === AluOp.Or)  -> (src1 | src2),
      (io.op === AluOp.Xor) -> (src1 ^ src2)
    )
  )

  io.aluOut := Mux(cmpSlt, cmpLT.asUInt, aout)

  io.brRel :=
    (io.br.bIfeq && cmpEQ) || (io.br.bIfne && ~cmpEQ) ||
      (io.br.bIflt && cmpLT) || (io.br.bIfge && ~cmpLT)
  io.brAbs := io.br.isAbs
  io.brDel := io.imm
  io.brVal := Mux(io.sel.brSelCsr, io.csrV, io.aluOut)

  when(io.aluEn) {
    printf(
      cf"[ EXU ] rs1 ${io.rs1V}%x rs2 ${io.rs2V}%x imm ${io.imm}%x Out ${io.aluOut}%x"
    )
    printf(cf" op ${io.op} sel ${io.sel}")
    printf(
      cf" (Br,Res) =!<G (${io.br.bIfeq}${io.br.bIfne}${io.br.bIflt}${io.br.bIfge},"
    )
    printf(
      cf"${cmpEQ}${!cmpEQ}${cmpLT}${!cmpLT}) usgn ${io.sel.cmpUsgn} abs ${io.br.isAbs}\n"
    )
  }
}

class ExecuteStage extends Module {
  val io = IO(new Bundle {
    val in      = Flipped(Decoupled(new DecodeToExecute))
    val flush   = Flipped(Decoupled(Bool()))
    val out     = Decoupled(new ExecuteToMemory)
    val toFetch = Decoupled(new ExecuteBackward)
    val brDet   = Decoupled(Bool())
  })

  val flushed = io.flush.bits
  // val flushed = RegInit(false.B)
  // flushed := MuxCase(
  //   flushed,
  //   Seq(
  //     io.flush.valid -> io.flush.bits,
  //     io.in.valid    -> false.B
  //   )
  // )
  io.flush.ready := io.out.ready

  val validCtrl = io.in.valid && !flushed

  io.in.ready  := io.out.ready
  io.out.valid := validCtrl

  val iExe = Module(new EXU)
  val ioid = io.in.bits
  val iols = io.out.bits
  iExe.io.op    := ioid.aluOp
  iExe.io.sel   := ioid.aluSel
  iExe.io.rs1V  := ioid.rs1V
  iExe.io.rs2V  := ioid.rs2V
  iExe.io.csrV  := ioid.foward.csrVal
  iExe.io.imm   := ioid.imm
  iExe.io.pc    := ioid.foward.pc
  iExe.io.aluEn := ioid.aluEn && validCtrl
  iExe.io.br    := ioid.brInst

  /** Back to Fetch */
  io.toFetch.valid := validCtrl
  val iobk = io.toFetch.bits
  iobk.brRel := iExe.io.brRel
  iobk.brDel := iExe.io.brDel
  iobk.brAbs := iExe.io.brAbs
  iobk.brVal := iExe.io.brVal
  iobk.brLPC := ioid.foward.pc

  /** Back to Decoder */
  io.brDet.valid := validCtrl
  // Must add this validCtrl
  io.brDet.bits  := validCtrl && (iExe.io.brRel || iExe.io.brAbs)

  /** To LSU, AluOut = Addr */
  iols.aluOut := iExe.io.aluOut
  iols.memOp  := ioid.memOp
  iols.rs2Val := ioid.rs2V

  /** Foward */
  ioid.foward <> iols.foward

  /** Interrupt */
  val iInt = Module(new EcallBox)
  iInt.io.clock    := clock
  iInt.io.reset    := reset
  iInt.io.a0in     := ioid.rs1V
  iInt.io.pcin     := ioid.foward.pc
  iInt.io.isEbreak := validCtrl && ioid.foward.ebreak
  iInt.io.isEcall  := validCtrl && ioid.foward.ecall
}
