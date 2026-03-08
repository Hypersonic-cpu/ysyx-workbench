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
  val tgtReady = unitReady && !sbAnyBusy

  // Accept from IDU when dispatch register is empty or
  // the current dispatch can fire
  io.decodeSide.ready := !dispValid || tgtReady

  // Capture / consume logic.
  // pipeFlush kills MUL/DIV in-flight but keeps the ALU
  // instruction so it can drain through EXU -> LSU (it is
  // the mispredicting branch that must still commit).
  val canFire = dispValid && tgtReady

  when(io.excpFlush) {
    dispValid := false.B
  }.elsewhen(io.pipeFlush) {
    when(isMD || !dispValid) {
      dispValid := false.B
    }.elsewhen(canFire) {
      // ALU fires this cycle during flush - consumed
      dispValid := false.B
    }
  }.elsewhen(canFire || !dispValid) {
    dispValid := io.decodeSide.valid
    when(io.decodeSide.valid) {
      dispBits := io.decodeSide.bits
    }
  }

  // Drive ALU output
  io.aluSide.valid         := dispValid && !isMD && !sbAnyBusy
  io.aluSide.bits.rs1V     := dispBits.rs1V
  io.aluSide.bits.rs2V     := dispBits.rs2V
  io.aluSide.bits.imm      := dispBits.imm
  io.aluSide.bits.pc       := dispBits.pc
  io.aluSide.bits.aluOp    := dispBits.aluOp
  io.aluSide.bits.aluSel   := dispBits.aluSel
  io.aluSide.bits.brInst   := dispBits.brInst
  io.aluSide.bits.memOp    := dispBits.memOp
  io.aluSide.bits.aluEn    := dispBits.aluEn
  io.aluSide.bits.predTaken  := dispBits.predTaken
  io.aluSide.bits.predTarget := dispBits.predTarget
  io.aluSide.bits.predBtbHit := dispBits.predBtbHit
  io.aluSide.bits.predBhtCnt := dispBits.predBhtCnt
  io.aluSide.bits.isCall   := dispBits.isCall
  io.aluSide.bits.isRet    := dispBits.isRet
  io.aluSide.bits.foward   := dispBits.foward

  // Drive MUL output
  io.mulSide.valid       := dispValid && isMulDisp &&
    !io.pipeFlush && !sbAnyBusy
  io.mulSide.bits.rs1    := dispBits.rs1V
  io.mulSide.bits.rs2    := dispBits.rs2V
  io.mulSide.bits.op     := dispBits.mulDivOp
  io.mulSide.bits.foward := dispBits.foward

  // Drive DIV output
  io.divSide.valid       := dispValid && isDivDisp &&
    !io.pipeFlush && !sbAnyBusy
  io.divSide.bits.rs1    := dispBits.rs1V
  io.divSide.bits.rs2    := dispBits.rs2V
  io.divSide.bits.op     := dispBits.mulDivOp
  io.divSide.bits.foward := dispBits.foward

  // Scoreboard
  val dispFire = dispValid && isMD && tgtReady &&
    !io.pipeFlush && !io.excpFlush
  val dispRd   = dispBits.foward.gprRd
  val sbSet    = Mux(
    dispFire && dispRd.orR,
    1.U(ISA.RegNum.W) << dispRd,
    0.U
  )

  when(io.pipeFlush || io.excpFlush) {
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
  */
class Collector extends Module {
  val io = IO(new Bundle {
    val aluSide = Flipped(Decoupled(new MemoryToWrBack))
    val mulSide = Flipped(Decoupled(new IntMulOut))
    val divSide = Flipped(Decoupled(new IntDivOut))
    val wbSide  = Decoupled(new MemoryToWrBack)
    val sbClear = Output(UInt(ISA.RegNum.W))
  })

  val divWins = io.divSide.valid && !io.aluSide.valid
  val mulWins = io.mulSide.valid && !io.divSide.valid &&
    !io.aluSide.valid
  val mdValid = divWins || mulWins

  // Convert MUL/DIV result to MemoryToWrBack
  val mdBits = Wire(new MemoryToWrBack)
  mdBits.aluOut := Mux(
    divWins,
    io.divSide.bits.result,
    io.mulSide.bits.result
  )
  mdBits.lsuOut := 0.U
  mdBits.foward := Mux(
    divWins,
    io.divSide.bits.foward,
    io.mulSide.bits.foward
  )

  when(io.aluSide.valid) {
    io.wbSide.valid := true.B
    io.wbSide.bits  := io.aluSide.bits
  }.elsewhen(mdValid) {
    io.wbSide.valid := true.B
    io.wbSide.bits  := mdBits
  }.otherwise {
    io.wbSide.valid := false.B
    io.wbSide.bits  := io.aluSide.bits
  }

  // ALU-path handshake
  io.aluSide.ready := io.wbSide.ready

  // MUL/DIV handshake: wait for ALU-path to drain
  io.divSide.ready := divWins && io.wbSide.ready &&
    !io.aluSide.valid
  io.mulSide.ready := mulWins && io.wbSide.ready &&
    !io.aluSide.valid

  // Scoreboard clear on MUL/DIV commit
  val mdFire = mdValid && io.wbSide.ready &&
    !io.aluSide.valid
  val mdRd   = Mux(
    divWins,
    io.divSide.bits.foward.gprRd,
    io.mulSide.bits.foward.gprRd
  )
  io.sbClear := Mux(
    mdFire && mdRd.orR,
    1.U(ISA.RegNum.W) << mdRd,
    0.U
  )
}
