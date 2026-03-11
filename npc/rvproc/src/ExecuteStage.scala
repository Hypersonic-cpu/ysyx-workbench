package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert

import BitMath._
import rvproc.AnsiColor.ColorString

// TODO: Rename To IntALU
class EXU extends Module {
  val io = IO(new Bundle {
    val aluEn   = Input(Bool())
    val rs1V    = Input(Tp.RegType())
    val rs2V    = Input(Tp.RegType())
    val aluSrc1 = Input(Tp.RegType())
    val aluSrc2 = Input(Tp.RegType())
    val csrV    = Input(Tp.RegType())
    val pc      = Input(Tp.RegType())
    val imm     = Input(Tp.RegType())
    val op      = Input(AluOp())
    val br      = Input(new BrInst())
    val sel     = Input(new AluSel)

    val aluOut = Output(Tp.RegType())

    val brRel = Output(Bool())
    val brAbs = Output(Bool())
    val brDel = Output(Tp.RegType())
    val brVal = Output(Tp.RegType())
  })

  // Tree comparator: O(log n) depth, no carry chain
  private def treeLTEq(
    a: UInt,
    b: UInt
  ): (Bool, Bool) = {
    val n = a.getWidth
    if (n == 1) {
      val lt = !a(0) && b(0)
      val eq = !(a(0) ^ b(0))
      (lt, eq)
    } else {
      val h        = n / 2
      val (hL, hE) =
        treeLTEq(a(n - 1, h), b(n - 1, h))
      val (lL, lE) =
        treeLTEq(a(h - 1, 0), b(h - 1, 0))
      (hL || (hE && lL), hE && lE)
    }
  }
  private def treeLTU(a: UInt, b: UInt): Bool =
    treeLTEq(a, b)._1

  val cmpSlt =
    io.op === AluOp.Sltu || io.op === AluOp.Slt

  val flip1 = io.sel.rs1Invert
  val flip2 = io.sel.rs2Invert
  val raw1  = io.aluSrc1
  val raw2  = io.aluSrc2
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

  // Tree comparator for branch conditions only,
  // bypassing the carry chain entirely.
  val brLTU = treeLTU(io.rs1V, io.rs2V)
  val msb   = ISA.RegBits - 1
  val brLTS = Mux(
    io.rs1V(msb) =/= io.rs2V(msb),
    io.rs1V(msb).asBool,
    brLTU
  )
  val brLT  = Mux(io.sel.cmpUsgn, brLTU, brLTS)

  io.brRel :=
    (io.br.bIfeq && cmpEQ) ||
      (io.br.bIfne && ~cmpEQ) ||
      (io.br.bIflt && brLT) ||
      (io.br.bIfge && ~brLT)
  io.brAbs := io.br.isAbs && io.br.isBr
  io.brDel := io.imm
  // Dedicated JALR adder bypasses ALU MUX/inversion
  val jalrTarget = io.rs1V + io.imm
  io.brVal := Mux(
    io.sel.brSelCsr,
    io.csrV,
    jalrTarget(ISA.RegBits - 1, 0)
  )
}

class ExecuteStage extends Module {
  val io = IO(new Bundle {
    val in        = Flipped(Decoupled(new IntAluIn))
    val out       = Decoupled(new ExecuteToMemory)
    // To FlushCtrl
    val brDet     = Decoupled(Bool())
    val brInfo    = Decoupled(new ExecuteBackward)
    val outFire   = Output(Bool()) // TODO: FIXME: Why this cannot be intergrated with brInfo.valid ?
    // From FlushCtrl
    val flush     = Input(Bool())
    val excpFlush = Input(Bool())
    // Forwarding
    val fwdDet    = Output(new FwBundle) // TODO: ?
  })

  val flushed   = io.flush
  val validCtrl =
    io.in.valid && !flushed

  io.in.ready := io.out.ready
  val outFire = validCtrl && !io.excpFlush &&
    io.out.ready
  io.outFire   := outFire
  io.out.valid := validCtrl && !io.excpFlush

  val iExe = Module(new EXU)
  val ioid = io.in.bits
  val iols = io.out.bits
  iExe.io.op      := ioid.aluOp
  iExe.io.sel     := ioid.aluSel
  iExe.io.rs1V    := ioid.rs1V
  iExe.io.rs2V    := ioid.rs2V
  iExe.io.aluSrc1 := ioid.aluSrc1
  iExe.io.aluSrc2 := ioid.aluSrc2
  iExe.io.csrV  := ioid.forward.csrVal
  iExe.io.imm   := ioid.imm
  iExe.io.pc    := ioid.pc // use pc directly (not forward.pc, which is 0 in non-debug)
  iExe.io.aluEn := ioid.aluEn && validCtrl
  iExe.io.br    := ioid.brInst

  /** Forward: ALU results forwarded from EXU */
  io.fwdDet.valid := validCtrl
  io.fwdDet.gprFw := ioid.forward.wbSel === WbSel.fromAlu
  io.fwdDet.gprDt := iExe.io.aluOut

  /** Back to Fetch */
  val actualTaken  = iExe.io.brRel || iExe.io.brAbs
  val actualTarget = Mux(
    iExe.io.brAbs,
    iExe.io.brVal,
    ioid.pc + iExe.io.brDel
  )

  val mispred = validCtrl && (
    (ioid.brInst.isBr && (
      (actualTaken =/= ioid.predTaken) ||
        (actualTaken && ioid.predTaken &&
          actualTarget =/= ioid.predTarget)
    )) ||
      (!ioid.brInst.isBr && ioid.predTaken)
  )

  if (GlbCtrl.debug) {
    val bpPmu = Module(new pmu.BrPredPMU)
    bpPmu.io.clock        := clock
    bpPmu.io.reset        := reset
    bpPmu.io.valid        := outFire && ioid.brInst.isBr
    bpPmu.io.predTaken    := ioid.predTaken
    bpPmu.io.actualTaken  := actualTaken
    bpPmu.io.predTarget   := ioid.predTarget
    bpPmu.io.actualTarget := actualTarget
    bpPmu.io.btbHit       := ioid.predBtbHit
    bpPmu.io.brPC         := ioid.pc
  }

  /** Back to Fetch - combinational wire, registered below */
  val toFWire = Wire(new ExecuteBackward)
  toFWire.brTaken    := actualTaken
  toFWire.brTarget   := actualTarget
  toFWire.brLPC      := ioid.pc
  toFWire.brLPC4     := ioid.pc + 4.U
  toFWire.isBr       := validCtrl && ioid.brInst.isBr
  toFWire.mispred    := mispred
  toFWire.predBtbHit := ioid.predBtbHit
  toFWire.predBhtCnt := ioid.predBhtCnt // FIXME: 当时为什么要写这个?
  toFWire.isCall     := ioid.isCall
  toFWire.isRet      := ioid.isRet

  /** To LSU, AluOut = Addr */
  iols.aluOut  := iExe.io.aluOut
  iols.memOp   := ioid.memOp
  iols.isMemEn := ioid.memOp.isEn
  iols.rs2Val  := ioid.rs2V

  /** Forward */
  ioid.forward <> iols.forward
  if (GlbCtrl.debug) {
    iols.forward.stallT := Mux(
      flushed,
      StallCause.Branch,
      ioid.forward.stallT
    )
  } else {
    iols.forward.stallT := DontCare
  }

  // Flush IDU/EXU on misprediction.
  // Gate with outFire: brDet only fires when the
  // instruction actually exits EXU. This prevents
  // repeated flushes while the skid buffer is full.
  val needFlush = mispred
  val brDetV    = outFire
  val brDetB    = outFire && needFlush

  io.brDet.valid  := brDetV
  io.brDet.bits   := brDetB
  io.brInfo.valid := brDetV
  io.brInfo.bits  := toFWire

  // Flush handled by FlushCtrl; regBrFlush removed

  /** Interrupt */
  if (!GlbCtrl.sta) {
    val iInt = Module(new EcallBox)
    iInt.io.clock    := clock
    iInt.io.reset    := reset
    iInt.io.a0in     := ioid.rs1V
    iInt.io.a5in     := ioid.rs2V
    iInt.io.pcin     := ioid.forward.pc
    iInt.io.isEbreak := validCtrl && ioid.forward.ebreak
    iInt.io.isEcall  := validCtrl && ioid.forward.ecall
    println("== Sim - EcallBox ===".green)
  } else {
    println("== STA - Fake Ecall ===".yellow)
  }
}
