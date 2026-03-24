package rvproc

import chisel3._
import chisel3.util._

/** Dispatch register: IDU -> {ALU, MUL, DIV}.
  *
  * Contains a pipeline register and a 32-bit scoreboard.
  * The register sits at the output side ("as late as
  * possible") so the ALU critical path is not extended.
  */
class Dispatcher extends Module {
  val io = IO(new Bundle {
    val decodeSide = Flipped(Decoupled(new DecodeToExecute))
    val aluSide    = Decoupled(new IntAluIn)
    val mulSide    = Decoupled(new IntMulIn)
    val divSide    = Decoupled(new IntDivIn)

    // Scoreboard
    val sbBusy  = Output(UInt(ISA.RegNum.W))
    val sbClear = Input(UInt(ISA.RegNum.W))

    // Flush
    val pipeFlush = Input(Bool())
    val excpFlush = Input(Bool())
  })

  val scoreboard = RegInit(0.U(ISA.RegNum.W))

  // Pipeline register
  val dispValid = RegInit(false.B)
  val dispBits  = Reg(new DecodeToExecute)

  val isMulDisp = dispBits.isMul
  val isDivDisp = dispBits.isDiv
  val isMD      = isMulDisp || isDivDisp
  val sbAnyBusy = scoreboard.orR

  // Target unit ready
  val unitReady = Mux(
    isMulDisp,
    io.mulSide.ready,
    Mux(isDivDisp, io.divSide.ready, io.aluSide.ready)
  )
  val tgtReady  = unitReady && Mux(isMD, !sbAnyBusy, true.B)

  // Accept from IDU when dispatch register is empty or
  // the current dispatch can fire
  io.decodeSide.ready := !dispValid || tgtReady

  // Capture / consume logic.
  // A MUL/DIV waiting due to sbAnyBusy is necessarily older
  // than any ALU instruction that could cause a branch
  // misprediction (BTB aliasing). Only excpFlush should
  // clear it; pipeFlush must not drop older instructions.
  val canFire       = dispValid && tgtReady
  val mdWaiting     = dispValid && isMD && sbAnyBusy
  val shouldFlush   = io.excpFlush || (io.pipeFlush && !mdWaiting)

  if (GlbCtrl.debug) {
    when(shouldFlush && dispValid && isMD) {
      printf("DISP FLUSH: pc=%x isMul=%d isDiv=%d sbBusy=%d pflush=%d exflush=%d mdWait=%d\n",
        dispBits.forward.pc, isMulDisp, isDivDisp, sbAnyBusy,
        io.pipeFlush, io.excpFlush, mdWaiting)
    }
    when(dispValid && isMD && io.pipeFlush && mdWaiting) {
      printf("DISP PROTECT: pc=%x blocked pipeFlush\n", dispBits.forward.pc)
    }
  }

  when(shouldFlush) {
    dispValid := false.B
  }.elsewhen(canFire || !dispValid) {
    dispValid := io.decodeSide.valid
    when(io.decodeSide.valid) {
      dispBits := io.decodeSide.bits
    }
  }

  // Forwarding done in IDU; dispBits.rs1V/rs2V are
  // already forwarded. Use directly.

  // Drive ALU output
  io.aluSide.valid           := dispValid && !isMD
  io.aluSide.bits.rs1V       := dispBits.rs1V
  io.aluSide.bits.rs2V       := dispBits.rs2V
  io.aluSide.bits.aluSrc1    := dispBits.aluSrc1
  io.aluSide.bits.aluSrc2    := dispBits.aluSrc2
  io.aluSide.bits.imm        := dispBits.imm
  io.aluSide.bits.pc         := dispBits.pc
  io.aluSide.bits.aluOp      := dispBits.aluOp
  io.aluSide.bits.aluSel     := dispBits.aluSel
  io.aluSide.bits.brInst     := dispBits.brInst
  io.aluSide.bits.memOp      := dispBits.memOp
  io.aluSide.bits.aluEn      := dispBits.aluEn
  io.aluSide.bits.predTaken  := dispBits.predTaken
  io.aluSide.bits.predTarget := dispBits.predTarget
  io.aluSide.bits.predBtbHit := dispBits.predBtbHit
  io.aluSide.bits.predBhtCnt := dispBits.predBhtCnt
  io.aluSide.bits.isCall     := dispBits.isCall
  io.aluSide.bits.isRet      := dispBits.isRet
  io.aluSide.bits.forward     := dispBits.forward

  // Drive MUL output
  io.mulSide.valid       := dispValid && isMulDisp &&
    !io.pipeFlush && !sbAnyBusy
  io.mulSide.bits.rs1    := dispBits.rs1V
  io.mulSide.bits.rs2    := dispBits.rs2V
  io.mulSide.bits.op     := dispBits.mulDivOp
  io.mulSide.bits.forward := dispBits.forward

  // Drive DIV output
  io.divSide.valid       := dispValid && isDivDisp &&
    !io.pipeFlush && !sbAnyBusy
  io.divSide.bits.rs1    := dispBits.rs1V
  io.divSide.bits.rs2    := dispBits.rs2V
  io.divSide.bits.op     := dispBits.mulDivOp
  io.divSide.bits.forward := dispBits.forward

  // Scoreboard
  val dispFire = dispValid && isMD && tgtReady &&
    !io.pipeFlush && !io.excpFlush
  val dispRd   = dispBits.forward.gprRd
  val sbSet    = Mux(
    dispFire && dispRd.orR,
    1.U(ISA.RegNum.W) << dispRd,
    0.U
  )

  when(io.excpFlush) {
    scoreboard := 0.U
  }.otherwise {
    scoreboard := (scoreboard | sbSet) & ~io.sbClear
  }
  // Combinational forwarding so IDU sees the set on
  // the same cycle a MUL/DIV dispatches
  io.sbBusy := scoreboard | sbSet

  if (GlbCtrl.debug) {
    dontTouch(dispValid)
    dontTouch(dispBits)
    dontTouch(scoreboard)
  }
}

/** Writeback collector: {LSU, MUL, DIV} -> WBU.
  *
  * ALU-path (through LSU) is always older in this
  * in-order pipeline and must commit first.
  * When ALU-path is idle: DIV > MUL priority.
  * pendingALU blocks MUL/DIV commit while older ALU
  * instructions are still draining through the pipeline.
  */
class Collector extends Module {
  val io = IO(new Bundle {
    val aluSide    = Flipped(Decoupled(new MemoryToWrBack))
    val mulSide    = Flipped(Decoupled(new IntMulOut))
    val divSide    = Flipped(Decoupled(new IntDivOut))
    val wbSide     = Decoupled(new MemoryToWrBack)
    val sbClear    = Output(UInt(ISA.RegNum.W))
    val pendingALU = Input(Bool())
    val excpFlush  = Input(Bool())
  })

  // excpFlush clears mdRegV directly; no hold-off needed.

  // Register MUL/DIV results to break timing from
  // div/mul state regs through forwarding to mainB.
  val mdRegV  = RegInit(false.B)
  val mdRegB  = Reg(new MemoryToWrBack)
  val mdRegRd = Reg(Tp.RegIdxType())

  // Accept MUL/DIV into register when slot is empty
  val isDivSrc  = io.divSide.valid
  val canAccept = !mdRegV
  io.divSide.ready := isDivSrc && canAccept
  io.mulSide.ready :=
    io.mulSide.valid && !isDivSrc && canAccept

  val mdAccept = io.divSide.fire || io.mulSide.fire

  val mdBits = Wire(new MemoryToWrBack)
  mdBits.aluOut := Mux(
    isDivSrc,
    io.divSide.bits.result,
    io.mulSide.bits.result
  )
  mdBits.lsuOut := 0.U
  mdBits.forward := Mux(
    isDivSrc,
    io.divSide.bits.forward,
    io.mulSide.bits.forward
  )

  val mdRdWire = Mux(
    isDivSrc,
    io.divSide.bits.forward.gprRd,
    io.mulSide.bits.forward.gprRd
  )

  // Output: ALU has priority, then registered MD
  val canMDOut =
    !io.aluSide.valid && !io.pendingALU
  val mdCommit = mdRegV && canMDOut && io.wbSide.ready

  when(io.aluSide.valid) {
    io.wbSide.valid := true.B
    io.wbSide.bits  := io.aluSide.bits
  }.elsewhen(mdRegV && canMDOut) {
    io.wbSide.valid := true.B
    io.wbSide.bits  := mdRegB
  }.otherwise {
    io.wbSide.valid := false.B
    io.wbSide.bits  := io.aluSide.bits
  }

  io.aluSide.ready := io.wbSide.ready

  // MD register update
  when(io.excpFlush) {
    mdRegV := false.B
  }.elsewhen(mdAccept) {
    mdRegV  := true.B
    mdRegB  := mdBits
    mdRegRd := mdRdWire
  }.elsewhen(mdCommit) {
    mdRegV := false.B
  }

  // Scoreboard clear on registered MD commit
  io.sbClear := Mux(
    mdCommit && mdRegRd.orR,
    1.U(ISA.RegNum.W) << mdRegRd,
    0.U
  )
}
