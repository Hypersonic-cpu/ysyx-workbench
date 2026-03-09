package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert
import rvproc.BitMath._
import rvproc.pmu.DecodePMU

object InstOp extends ChiselEnum {
  val Load    = Value("b00000".U)
  val MiscM   = Value("b00011".U)
  val OpImm   = Value("b00100".U)
  val Auipc   = Value("b00101".U)
  val Store   = Value("b01000".U)
  val OpReg   = Value("b01100".U)
  val Lui     = Value("b01101".U)
  val OpFP    = Value("b10100".U)
  val Branch  = Value("b11000".U)
  val Jalr    = Value("b11001".U)
  val Reserve = Value("b11010".U)
  val Jal     = Value("b11011".U)
  val System  = Value("b11100".U)
}

object CsrOp extends ChiselEnum {
  val None  = Value("b00".U)
  val CsrRW = Value("b01".U)
  val CsrRS = Value("b10".U)
  val CsrRC = Value("b11".U)
}

class IDU extends Module {
  val io = IO(new Bundle {
    val valid  = Input(Bool())
    val inst   = Input(Tp.InstType())
    val pc     = Input(Tp.RegType())
    val rd     = Output(Tp.RegIdxType())
    val csriw  = Output(Tp.CsrIdxType())
    val imm    = Output(Tp.RegType())
    val gprWE  = Output(Bool())
    val csrWE  = Output(Bool())
    val csralu = Output(Bool())
    val memAcc = Output(new MemOp)
    val aluEn  = Output(Bool())
    val aluOp  = Output(AluOp())
    val aluSel = Output(new AluSel)
    val fenceI = Output(Bool())

    val brInst   = Output(new BrInst)
    val isCall   = Output(Bool())
    val isRet    = Output(Bool())
    val isMul    = Output(Bool())
    val isDiv    = Output(Bool())
    val mulDivOp = Output(MulDivOp())

    val wbSel  = Output(WbSel())
    val ebreak = Output(Bool())
    val ecall  = Output(Bool())

    // always_comb, to reg
    val rs1   = Output(Tp.RegIdxType())
    val rs2   = Output(Tp.RegIdxType())
    val csrir = Output(Tp.CsrIdxType())

    // for PMU
    val opname  = Output(InstOp())
    val opValid = Output(Bool())
  })

  val opcode  = io.inst(6, 0)
  val funct3  = io.inst(14, 12)
  val funct7  = io.inst(31, 25)
  val rvBase  = opcode(1, 0) === "b11".U(2.W)
  val csrid12 = io.inst(31, 20)

  val (opName, opValid) = InstOp.safe(opcode(6, 2))
  io.opname  := Mux(opValid, opName, InstOp.Reserve);
  io.opValid := opValid
  val sysRel   =
    (opName === InstOp.System) && ~io.inst(19, 7).orR
  val isEbreak = sysRel && csrid12 === 1.U
  val isEcall  = sysRel && csrid12 === 0.U
  val isMret   = sysRel && csrid12 === "b_0011000_00010".U
  val isFenceI = opName === InstOp.MiscM && funct3 === "b001".U

  io.ebreak := isEbreak
  io.ecall  := isEcall
  io.fenceI := isFenceI

  // On Ebreak we prepare reg a0 (x10)
  // On Ecall  we prepare reg a5 (x15)
  io.rs1 := MuxCase(
    io.inst(19, 15),
    Seq(
      isEbreak                -> 10.U,
      // isEcall                 -> 15.U,
      (opName === InstOp.Lui) -> 0.U
    )
  )

  // (isEbreak, 10.U, io.inst(19, 15))
  io.rs2 := Mux(isEbreak, 15.U, io.inst(24, 20))
  io.rd  := io.inst(11, 7)
  val immI = io.inst(31, 20).SExt()
  io.csrir := MuxCase(
    immI(11, 0),
    Seq(
      isEcall -> 0x305.U, // mtvec
      isMret  -> 0x341.U  // mepc
    )
  )
  io.csriw := csrid12

  val immU = io.inst(31, 12) << 12
  val immS =
    (io.inst(31, 25) ## io.inst(11, 7)).SExt()
  val immJ =
    (io.inst(31, 31) ## io.inst(19, 12) ##
      io.inst(20, 20) ## io.inst(30, 21) ## 0.U(1.W)).SExt()
  val immB =
    (io.inst(31, 31) ## io.inst(7, 7) ##
      io.inst(30, 25) ## io.inst(11, 8) ## 0.U(1.W)).SExt()

  val instTp    = MuxLookup(opName, ITYPE.tN)(
    Seq(
      InstOp.OpImm  -> ITYPE.tI,
      InstOp.OpReg  -> ITYPE.tR,
      InstOp.Jalr   -> ITYPE.tI,
      InstOp.Jal    -> ITYPE.tJ,
      InstOp.Lui    -> ITYPE.tU,
      InstOp.Auipc  -> ITYPE.tU,
      InstOp.Load   -> ITYPE.tI,
      InstOp.Store  -> ITYPE.tS,
      InstOp.Branch -> ITYPE.tB,
      InstOp.System -> ITYPE.tN
    )
  )
  val instArith =
    opName === InstOp.OpReg || opName === InstOp.OpImm
  val instBr    = opName === InstOp.Branch
  val instSys   = opName === InstOp.System
  val sysOp     = CsrOp(funct3(1, 0))
  val instCsr   = instSys && (sysOp =/= CsrOp.None)
  io.csralu := instCsr && sysOp =/= CsrOp.CsrRW

  // M-extension: OpReg with funct7 = 0000001
  val instMext =
    opName === InstOp.OpReg && funct7 === "b0000001".U
  val mulDivOp = MulDivOp(funct3)
  val instMul  = instMext && !funct3(2)
  val instDiv  = instMext && funct3(2)
  io.isMul    := instMul
  io.isDiv    := instDiv
  io.mulDivOp := mulDivOp

  /** ALU commands -> EXU */
  val aluEn = !isFenceI && !instMext
  val aluOp = MuxCase(
    AluOp.Add,
    Seq(
      instArith                          -> AluOp(funct3),
      (instCsr && sysOp === CsrOp.CsrRW) -> AluOp.Add,
      (instCsr && sysOp === CsrOp.CsrRC) -> AluOp.And,
      (instCsr && sysOp === CsrOp.CsrRS) -> AluOp.Or
    )
  )

  io.aluOp            := aluOp
  io.aluEn            := aluEn
  io.aluSel.rs1SelPC  :=
    opName === InstOp.Auipc ||
      opName === InstOp.Jal
  io.aluSel.rs2SelImm := instTp =/= ITYPE.tR && instTp =/= ITYPE.tB
  io.aluSel.brSelCsr  := isEcall || isMret

  io.aluSel.rs1Invert := instCsr && sysOp === CsrOp.CsrRC
  io.aluSel.rs2Invert :=
    (
      // Op-Reg : SUB, not SRA
      opName === InstOp.OpReg &&
        aluOp === AluOp.Add && funct7(5).asBool
    ) || (
      // SLT(I)(U)
      instArith && (aluOp === AluOp.Sltu || aluOp === AluOp.Slt)
    ) || instBr
  io.aluSel.shArith   := instArith && aluOp === AluOp.Srr && funct7(5)
  io.aluSel.cmpUsgn   :=
    (instArith && aluOp === AluOp.Sltu) ||
      (instBr && funct3(1).asBool)

  // imm is always sign-extended
  io.imm := MuxLookup(instTp, 0.U)(
    Seq(
      ITYPE.tI -> immI,
      ITYPE.tU -> immU,
      ITYPE.tS -> immS,
      ITYPE.tJ -> immJ,
      ITYPE.tB -> immB
    )
  )

  // Memory options -> EXU -> LSU
  io.memAcc.len  := MemLen(
    Mux(
      opName === InstOp.Load || opName === InstOp.Store,
      funct3(1, 0),
      "b11".U
    )
  )
  io.memAcc.isSt := instTp === ITYPE.tS
  io.memAcc.sExt := ~funct3(2)

  val isJal  = opName === InstOp.Jal
  val isJalr = opName === InstOp.Jalr
  io.brInst.isAbs := isJalr || isEcall || isMret
  io.brInst.bIfeq := (instBr && funct3 === "b000".U) || isJal
  io.brInst.bIfne := (instBr && funct3 === "b001".U) || isJal
  io.brInst.bIflt := instBr && ((funct3 & "b101".U) === "b100".U)
  io.brInst.bIfge := instBr && ((funct3 & "b101".U) === "b101".U)
  io.brInst.isBr  := instBr || io.brInst.isAbs || isJal

  val rs1Idx = io.inst(19, 15)
  io.isCall := (isJal || isJalr) && io.rd === 1.U
  io.isRet  := isJalr && rs1Idx === 1.U && io.rd === 0.U

  io.wbSel := MuxCase(
    WbSel.fromAlu,
    Seq(
      instCsr                  -> WbSel.fromCsr,
      (opName === InstOp.Jalr) -> WbSel.fromPC,
      (opName === InstOp.Jal)  -> WbSel.fromPC,
      (opName === InstOp.Load) -> WbSel.fromMem
    )
  )

  io.gprWE := ~(
    instTp === ITYPE.tN ||
      instTp === ITYPE.tB ||
      instTp === ITYPE.tS ||
      isFenceI
  ) || instCsr

  /** CSRRC: R[rd] = CSR, CSR &= ~R[rs1] = ~src1 & csr CSRRS: R[rd] =
    * CSR, CSR |= R[rs1] = src1 | csr CSRRW: R[rd] = CSR, CSR = R[rs1] =
    * 0 + csr We directly pass 0 + src1 to ALU and use ALU result as
    * csrdt.
    */
  // RISC-V spec: CSRRS/CSRRC (and CSRRSI/CSRRCI) with rs1/uimm=0 must NOT write CSR
  val csrNoWrite =
    (sysOp === CsrOp.CsrRS || sysOp === CsrOp.CsrRC) && io.rs1 === 0.U
  io.csrWE := instCsr && !csrNoWrite
}

class DecodeStage extends Module {
  val io = IO(new Bundle {
    val in      = Flipped(Decoupled(new FetchToDecode))
    val out     = Decoupled(new DecodeToExecute)
    // always_comb
    val fromReg = Flipped(Decoupled(new RegToIDU))
    val toReg   = Decoupled(new RegFromIDU)

    val fenceI    = Decoupled(Bool())
    val flush     = Input(Bool())
    val rawSrc    = new DecodeHazard
    val fwdRes    = Input(new SourceFoward)
    val excpFlush = Input(Bool())
    val sbBusy    = Input(UInt(ISA.RegNum.W))
    val rawStall =
      if (GlbCtrl.debug) Some(Output(Bool())) else None
  })

  val flushed = io.flush

  val validCtrl = io.in.valid && !flushed

  val iDec = Module(new IDU)

  val waitRAW  = io.fwdRes.block
  // Scoreboard stall: stall if any used source is in-flight
  val sbHitRs1 = io.sbBusy(iDec.io.rs1) && validCtrl &&
    !iDec.io.aluSel.rs1SelPC && iDec.io.rs1.orR
  val sbHitRs2 = io.sbBusy(iDec.io.rs2) && validCtrl &&
    (!iDec.io.aluSel.rs2SelImm ||
      iDec.io.memAcc.isSt || iDec.io.ebreak) &&
    iDec.io.rs2.orR
  val waitSB   = sbHitRs1 || sbHitRs2
  io.rawSrc.rs1  := iDec.io.rs1
  io.rawSrc.rs2  := iDec.io.rs2
  io.rawSrc.csr  := iDec.io.csrir
  io.rawSrc.use1 := validCtrl && !iDec.io.aluSel.rs1SelPC
  io.rawSrc.use2 := validCtrl && (!iDec.io.aluSel.rs2SelImm || iDec.io.memAcc.isSt || iDec.io.ebreak)
  io.rawSrc.useC := validCtrl &&
    (iDec.io.wbSel === WbSel.fromCsr || iDec.io.aluSel.brSelCsr)

  io.in.ready     := io.out.ready && !waitRAW && !waitSB
  // Flush IF and ID when brAbs (result on )
  io.out.valid    := validCtrl && !waitRAW && !waitSB && !io.excpFlush
  io.fenceI.valid := validCtrl && !waitRAW && !waitSB && !io.excpFlush

  iDec.io.valid := validCtrl

  /** fence.i */
  io.fenceI.bits := iDec.io.fenceI

  /** Reg Read */
  io.toReg.valid     := io.in.valid
  io.toReg.bits.rs1  := iDec.io.rs1
  io.toReg.bits.rs2  := iDec.io.rs2
  io.toReg.bits.csrr := iDec.io.csrir
  io.fromReg.ready   := true.B
  val rs1Val =
    Mux(io.fwdRes.rs1fw, io.fwdRes.rs1dt, io.fromReg.bits.rs1Val)
  val rs2Val =
    Mux(io.fwdRes.rs2fw, io.fwdRes.rs2dt, io.fromReg.bits.rs2Val)
  val csrVal = io.fromReg.bits.csrVal

  /** Input from FetchStage */
  val ioif = io.in.bits
  iDec.io.inst := ioif.inst
  iDec.io.pc   := ioif.pc

  /** To ExecuteStage */
  val ioex = io.out.bits
  ioex.imm        := iDec.io.imm
  ioex.rs1V       := rs1Val
  ioex.rs2V       := Mux(iDec.io.csralu, csrVal, rs2Val)
  ioex.aluOp      := iDec.io.aluOp
  ioex.aluSel     := iDec.io.aluSel
  ioex.memOp      := iDec.io.memAcc
  ioex.aluEn      := iDec.io.aluEn
  ioex.brInst     := iDec.io.brInst
  ioex.pc         := ioif.pc
  ioex.predTaken  := ioif.predTaken
  ioex.predTarget := ioif.predTarget
  ioex.predBtbHit := ioif.predBtbHit
  ioex.predBhtCnt := ioif.predBhtCnt
  ioex.isCall     := iDec.io.isCall
  ioex.isRet      := iDec.io.isRet
  ioex.isMul      := iDec.io.isMul
  ioex.isDiv      := iDec.io.isDiv
  ioex.mulDivOp   := iDec.io.mulDivOp

  val iofw = ioex.foward
  iofw.gprRd  := iDec.io.rd
  iofw.csrRd  := iDec.io.csriw
  iofw.gprWE  := iDec.io.gprWE
  iofw.csrWE  := iDec.io.csrWE
  iofw.wbSel  := iDec.io.wbSel
  iofw.ebreak := iDec.io.ebreak
  iofw.ecall  := iDec.io.ecall
  iofw.fenceI := iDec.io.fenceI
  iofw.pc     := ioif.pc
  iofw.snpc   := ioif.pc + 4.U
  iofw.inst   := io.in.bits.inst
  iofw.csrVal := csrVal

  // Exception detection
  val mtvecVal     = io.fromReg.bits.mtvecVal
  val pcMisaligned = ioif.pc(1, 0).orR
  val ifuExcp      = ioif.ifuExcp
  val rvBase       = ioif.inst(1, 0) === "b11".U(2.W)
  val isIllegal    = !iDec.io.opValid || !rvBase
  val isIdExcp     =
    validCtrl && (pcMisaligned || ifuExcp || isIllegal)
  val isEcall      = iDec.io.ecall
  val isExcp       = isIdExcp || isEcall

  iofw.excpValid := isExcp
  iofw.excpFlush := false.B
  iofw.mcause    := MuxCase(
    0.U,
    Seq(
      pcMisaligned -> 0.U,
      ifuExcp      -> ioif.ifuExcpCause,
      isIllegal    -> 2.U,
      isEcall      -> 11.U
    )
  )

  // Override decode for non-ecall IDU exceptions
  when(isIdExcp) {
    ioex.aluSel.brSelCsr := true.B
    ioex.brInst.isAbs    := true.B
    ioex.brInst.isBr     := true.B
    ioex.memOp.len       := MemLen.None
    iofw.gprWE           := false.B
    iofw.csrWE           := false.B
    iofw.csrVal          := mtvecVal
  }

  if (GlbCtrl.debug) {
    iofw.stallT := Mux(
      waitRAW,
      StallCause.RAW,
      Mux(flushed, StallCause.Branch, StallCause.InstFetch)
    )
    io.rawStall.get := (waitRAW || waitSB) && validCtrl
  } else {
    iofw.stallT := DontCare
  }

  if (GlbCtrl.debug) {

    /** PMU related */
    val pmu = Module(new DecodePMU)
    pmu.io.clock     := clock
    pmu.io.reset     := reset
    pmu.io.isNewInst := io.in.fire
    pmu.io.instOp    := iDec.io.opname
    pmu.io.isFlush   := flushed
    pmu.io.pc        := io.in.bits.pc
  }
}
