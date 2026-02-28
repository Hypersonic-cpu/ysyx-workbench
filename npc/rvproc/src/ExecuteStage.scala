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

  val flip1 = io.sel.rs1Invert
  val flip2 = io.sel.rs2Invert
  val raw1  = Mux(io.sel.rs1SelPC, io.pc, io.rs1V)
  val raw2  = Mux(io.sel.rs2SelImm, io.imm, io.rs2V)
  val src1  = Mux(flip1, ~raw1, raw1)
  val src2  = Mux(flip2, ~raw2, raw2)

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
  io.brAbs := io.br.isAbs && io.br.isBr
  io.brDel := io.imm
  io.brVal := Mux(io.sel.brSelCsr, io.csrV, io.aluOut)

  // when(io.aluEn) {
  //   printf(
  //     cf"[ EXU ] rs1 ${io.rs1V}%x rs2 ${io.rs2V}%x imm ${io.imm}%x Out ${io.aluOut}%x"
  //   )
  //   printf(cf" op ${io.op} sel ${io.sel}")
  //   printf(
  //     cf" (Br,Res) =!<G (${io.br.bIfeq}${io.br.bIfne}${io.br.bIflt}${io.br.bIfge},"
  //   )
  //   printf(
  //     cf"${cmpEQ}${!cmpEQ}${cmpLT}${!cmpLT}) usgn ${io.sel.cmpUsgn} abs ${io.br.isAbs}\n"
  //   )
  // }
}

class ExecuteStage extends Module {
  val io = IO(new Bundle {
    val in      = Flipped(Decoupled(new DecodeToExecute))
    val flush   = Flipped(Decoupled(Bool()))
    val out     = Decoupled(new ExecuteToMemory)
    val toFetch = Decoupled(new ExecuteBackward)
    val brDet   = Decoupled(Bool())
    val fwdDet  = Output(new FwBundle)
  })

  val flushed = io.flush.valid && io.flush.bits
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
  iExe.io.pc    := ioid.pc      // use pc directly (not foward.pc, which is 0 in non-debug)
  iExe.io.aluEn := ioid.aluEn && validCtrl
  iExe.io.br    := ioid.brInst

  /** Forward */
  io.fwdDet.valid := validCtrl
  io.fwdDet.gprFw := false.B // !ioid.memOp.isEn
  io.fwdDet.gprDt := 0.U     // iExe.io.aluOut

  /** Back to Fetch */
  io.toFetch.valid := validCtrl
  val iobk = io.toFetch.bits
  iobk.brRel := iExe.io.brRel
  iobk.brDel := iExe.io.brDel
  iobk.brAbs := iExe.io.brAbs
  iobk.brVal := iExe.io.brVal
  iobk.brLPC := ioid.pc   // use pc directly (works in both debug and non-debug)

  // Misprediction detection: actual outcome vs BP prediction
  val actualTaken  = iExe.io.brRel || iExe.io.brAbs
  val actualTarget = Mux(
    iExe.io.brAbs,
    iExe.io.brVal,
    ioid.pc + iExe.io.brDel
  )
  val mispred = validCtrl && ioid.brInst.isBr && (
    (actualTaken =/= ioid.predTaken) ||
      (actualTaken && ioid.predTaken && actualTarget =/= ioid.predTarget)
  )
  iobk.isBr    := validCtrl && ioid.brInst.isBr
  iobk.mispred := mispred

  if (GlbCtrl.debug) {
    val bpPmu = Module(new pmu.BrPredPMU)
    bpPmu.io.clock        := clock
    bpPmu.io.reset        := reset
    bpPmu.io.valid        := validCtrl && ioid.brInst.isBr
    bpPmu.io.predTaken    := ioid.predTaken
    bpPmu.io.actualTaken  := actualTaken
    bpPmu.io.predTarget   := ioid.predTarget
    bpPmu.io.actualTarget := actualTarget
    bpPmu.io.btbHit       := ioid.predBtbHit

    val dbgCycE = RegInit(0.U(32.W))
    dbgCycE := dbgCycE + 1.U
    when(dbgCycE > 21290000.U) {
      when(validCtrl) {
        printf(cf"[EXU@${dbgCycE}] pc=0x${ioid.pc}%x inst=0x${ioid.foward.inst}%x isBr=${ioid.brInst.isBr} predT=${ioid.predTaken} actT=${actualTaken} mispred=${mispred}\n")
      }
      when(flushed) {
        printf(cf"[EXU-FL@${dbgCycE}] flushed instruction\n")
      }
    }
  }

  /** Back to Decoder */
  io.brDet.valid := validCtrl
  io.brDet.bits  := validCtrl && mispred

  /** To LSU, AluOut = Addr */
  iols.aluOut := Mux(
    ioid.foward.wbSel === WbSel.fromPC,
    ioid.pc,
    iExe.io.aluOut
  )
  iols.memOp  := ioid.memOp
  iols.rs2Val := ioid.rs2V

  /** Foward */
  ioid.foward <> iols.foward
  if (GlbCtrl.debug) {
    iols.foward.stallT := Mux(
      flushed,
      StallCause.Branch,
      ioid.foward.stallT
    )
  } else {
    iols.foward.stallT := DontCare
  }

  /** Interrupt */
  val iInt = Module(new EcallBox)
  iInt.io.clock    := clock
  iInt.io.reset    := reset
  iInt.io.a0in     := ioid.rs1V
  iInt.io.a5in     := ioid.rs2V
  iInt.io.pcin     := ioid.foward.pc
  iInt.io.isEbreak := validCtrl && ioid.foward.ebreak
  iInt.io.isEcall  := validCtrl && ioid.foward.ecall
}
