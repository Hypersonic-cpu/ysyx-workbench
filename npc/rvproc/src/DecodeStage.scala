package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert
import rvproc.BitMath._

object InstOp extends ChiselEnum {
  val Load   = Value(0b00000.U)
  val OpImm  = Value(0b00100.U)
  val Auipc  = Value(0b00101.U)
  val Store  = Value(0b01000.U)
  val OpReg  = Value(0b01100.U)
  // val OpFP   = Value(0b10100.U)
  val Lui    = Value(0b01101.U)
  val Branch = Value(0b11000.U)
  val Jalr   = Value(0b11001.U)
  val Jal    = Value(0b11011.U)
  val System = Value(0b11100.U)
}

object CsrOp extends ChiselEnum {
  val None  = Value(0b00.U)
  val CsrRW = Value(0b01.U)
  val CsrRS = Value(0b10.U)
  val CsrRC = Value(0b11.U)
}

class Comparator extends Module {
  val io = IO(new Bundle {
    val in1  = Input(Tp.RegType())
    val in2  = Input(Tp.RegType())
    val out  = Output(new BrCmp)
  })
  val cmp1s = io.in1
  val cmp2s = ~io.in2
  val cmpSum = 1.U + cmp1s.UExt() + cmp2s.UExt()
  val cmpOF = (
    ~(cmp1s.MSB() ^ cmp2s.MSB())) & 
     (cmp1s.MSB() ^ cmpSum.MSB())
  val cmpLTU = ~cmpSum.MSB(-1).asBool
  val cmpLTS = (cmpSum.MSB() ^ cmpOF).asBool
  val cmpEQ = ~cmpSum(ISA.RegBits-1, 0).orR.asBool
  io.out.bltu := cmpLTU
  io.out.blts := cmpLTS
  io.out.beq  := cmpEQ

  // io.takeBr := 
  //   (cmpLT && b.bIflt) || (~cmpLT && b.bIfge) ||
  //   (cmpEQ && b.bIfeq) || (~cmpEQ && b.bIfne)
  //
}

class IDU extends Module {
  val io = IO(new Bundle {
    val inst   = Input(Tp.InstType())
    val pc     = Input(Tp.RegType())
    val rd     = Output(Tp.RegIdxType())
    val csriw  = Output(Tp.CsrIdxType())
    val imm    = Output(Tp.RegType())
    val gprWE  = Output(Bool())
    val csrWE  = Output(Bool())
    val memAcc = Output(new MemOp)
    val aluEn  = Output(Bool())
    val aluOp  = Output(AluOp())
    val aluSel = Output(new AluSel)
    // val brJmp  = Output(new BrJmp)

    // br dest = imm
    val brRel  = Output(Bool())
    val brAbs  = Output(Bool())

    val wbSel  = Output(WbSel())
    val ebreak = Output(Bool())
    val ecall  = Output(Bool())

    // always_comb, to reg
    val rs1    = Output(Tp.RegIdxType())
    val rs2    = Output(Tp.RegIdxType())
    val csrir  = Output(Tp.CsrIdxType())

    val rs1Val = Input(Tp.RegType())
    val rs2Val = Input(Tp.RegType())
    // val csrVal = Input(Tp.RegType())
  })


  val opcode = io.inst(6, 0)
  val funct3 = io.inst(14, 12)
  val funct7 = io.inst(31, 25)
  val rvBase  = opcode(1, 0) === 0b11.U(2.W)
  val csrid12 = io.inst(31, 20)

  val (opName, opValid) = InstOp.safe(opcode(6, 2))
  printf(cf"[ ${io.pc}%x ID ] ${opName} rs1 ${io.rs1} rs2 ${io.rs2} rd ${io.rd}\n")

  assert(rvBase, cf"Inst[1:0] is not 0b11: opcode=${opcode}%x")
  assert(opValid, cf"Invalid opcode encountered: opcode=${opcode}%x")

  val sysRel = 
    (opName === InstOp.System) && ~io.inst(19, 7).orR
  val isEbreak = sysRel && csrid12 === 1.U
  val isEcall  = sysRel && csrid12 === 0.U
  val isMret   = sysRel && csrid12 === 0b_0011000_00010.U

  io.ebreak := isEbreak
  io.ecall  := isEcall

  // On Ebreak we prepare reg a0 (x10)
  // On Ecall  we prepare reg a5 (x15)
  io.rs1    := MuxCase(io.inst(19, 15), Seq(
    isEbreak                -> 10.U,
    // isEcall                 -> 15.U,
    (opName === InstOp.Lui) -> 0.U
  ))

  // (isEbreak, 10.U, io.inst(19, 15))
  io.rs2    := io.inst(24, 20)
  io.rd     := io.inst(11,  7)
  val immI   = io.inst(31, 20).SExt()
  io.csrir  := MuxCase(immI(11, 0), Seq(
    isEcall -> 0x305.U,
    isMret  -> 0x341.U
  ))
  io.csriw  := Mux(isEcall, 0x341.U, csrid12)

  val immU   = io.inst(31, 12) << 12
  val immS   =
    (io.inst(31, 25) ## io.inst(11, 7)).SExt()
  val immJ   =
    (io.inst(31, 31) ## io.inst(19, 12) ##
      io.inst(20, 20) ## io.inst(30, 21) ## 0.U(1.W)).SExt()
  val immB =
    (io.inst(31, 31) ## io.inst(7, 7) ##
      io.inst(30, 25) ## io.inst(11, 8) ## 0.U(1.W)).SExt()

  val instTp  = MuxLookup(opName, ITYPE.tX) ( Seq(
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
    ))
  val instArith =
    opName === InstOp.OpReg || opName === InstOp.OpImm
  val instBr  = opName === InstOp.Branch
  val instSys = opName === InstOp.System
  val sysOp   = Mux(isEcall, 
    CsrOp.CsrRW, CsrOp(funct3(1, 0)))
  val instCsr = instSys && (sysOp =/= CsrOp.None)

  /** NOTE: ALU commands -> EXU */
  val aluEn = (instTp =/= ITYPE.tB) && (opName =/= InstOp.Jalr)
  val aluOp = MuxCase (AluOp.Add, Seq(
    instArith -> AluOp(funct3),
    // instBr    -> Mux(funct3(1), AluOp.Sltu, AluOp.Slt),
    (instCsr && sysOp === CsrOp.CsrRW) -> AluOp.Add,
    (instCsr && sysOp === CsrOp.CsrRC) -> AluOp.And,
    (instCsr && sysOp === CsrOp.CsrRS) -> AluOp.Or,
  ))
  // val instSlt = 
  //   instArith && (aluOp === AluOp.Slt || aluOp === AluOp.Sltu)
  // io.aluSel.saveCmp   := instSlt

  io.aluOp := aluOp
  io.aluEn := aluEn
  // io.aluSel.cmpImm    := instSlt && instTp === ITYPE.tI
  io.aluSel.rs1SelPC  :=
    opName === InstOp.Auipc || 
    opName === InstOp.Jal ||
    isEcall
    // opName === InstOp.Jalr || 
    // || instBr
  io.aluSel.rs2SelImm := instTp =/= ITYPE.tR 
  io.aluSel.brSelCsr  := isEcall || isMret

  io.aluSel.rs1Invert := instCsr && sysOp === CsrOp.CsrRC
  io.aluSel.rs2Invert :=
    (opName === InstOp.OpReg && funct7(5).asBool) ||
    (instArith && io.aluOp === AluOp.Srr && funct7(5).asBool)

  // imm is always sign-extended
  io.imm    := MuxLookup(instTp, 0.U) (Seq(
    ITYPE.tI -> immI,
    ITYPE.tU -> immU,
    ITYPE.tS -> immS,
    ITYPE.tJ -> immJ,
    ITYPE.tB -> immB
  ))
  io.brAbs := (opName === InstOp.Jalr) || isEcall || isMret


  /** NOTE: Memory options -> EXU -> LSU */
  io.memAcc.len := MemLen(Mux(
    opName === InstOp.Load || opName === InstOp.Store,
    funct3(1, 0), 0b11.U
  ))
  io.memAcc.isSt := instTp === ITYPE.tS
  io.memAcc.sExt := ~funct3(2)

  /** NOTE: Compare and relative branch -> IFU */
  val isJal = opName === InstOp.Jal
  val bIfeq = (instBr && funct3 === 0b000.U) || isJal
  val bIfne = (instBr && funct3 === 0b001.U) || isJal
  val bIflt = instBr && ((funct3 & 0b101.U) === 0b100.U)
  val bIfge = instBr && ((funct3 & 0b101.U) === 0b101.U)

  val iCmp = Module(new Comparator)
  iCmp.io.in1 := io.rs1Val
  iCmp.io.in2 := io.rs2Val
  val brCmp = iCmp.io.out
  val brEq  = brCmp.beq
  val brLt  = Mux(instBr && aluOp === AluOp.Sltu, 
    brCmp.bltu, brCmp.blts)
  io.brRel := 
    (bIfeq && brEq) || (bIfne && ~brEq) ||
    (bIflt && brLt) || (bIfge && ~brLt)
  // brDel = imm

  /** NOTE: Foward -> WBU */
  io.wbSel := MuxCase(WbSel.fromAlu, Seq(
    instCsr -> WbSel.fromCsr,
    (opName === InstOp.Jalr) -> WbSel.fromPC,
    (opName === InstOp.Jal ) -> WbSel.fromPC,
    (opName === InstOp.Load) -> WbSel.fromMem
  ))

  io.gprWE  := ~(
    instTp === ITYPE.tN ||
    instTp === ITYPE.tB ||
    instTp === ITYPE.tS
  ) || instCsr

  /**
    * CSRRC: R[rd] = CSR, CSR &= ~R[rs1] = ~src1 & csr
    * CSRRS: R[rd] = CSR, CSR |=  R[rs1] =  src1 | csr
    * CSRRW: R[rd] = CSR, CSR  =  R[rs1] =     0 + csr
    * We directly pass 0 + src1 to ALU and use ALU result
    * as csrdt.
    */
  io.csrWE := instCsr


}


class DecodeStage extends Module {
  val io = IO(new Bundle{
    val in  = Flipped(Decoupled(new FetchToDecode))
    val out = Decoupled(new DecodeToExecute)
    // always_comb
    val fromReg = Flipped(Decoupled(new RegToIDU()))
    val toReg   = Decoupled(new RegFromIDU())

    val toFetch = Decoupled(new DecodeBackward)
  })

  // wait for NEXT stage
  // val idle :: hold :: Nil = Enum(2)
  // TODO:
  io.in.ready  := true.B
  io.out.valid := true.B
  io.toFetch.valid := true.B
  // val state = RegInit(wait)
  // state := MuxLookup(state, wait) (Seq(
  //   idle   -> Mux(io.out.valid, ),
  //   waitIF  -> Mux(io.in.ready, ready)
  // ))

  val iDec = Module(new IDU)

  /** NOTE: Reg Read */
  io.toReg.valid := true.B
  io.toReg.bits.rs1  := iDec.io.rs1
  io.toReg.bits.rs2  := iDec.io.rs2
  io.toReg.bits.csrr := iDec.io.csrir
  io.fromReg.ready := true.B
  val rs1Val = io.fromReg.bits.rs1Val
  val rs2Val = io.fromReg.bits.rs2Val
  val csrVal = io.fromReg.bits.csrVal
  iDec.io.rs1Val := rs1Val
  iDec.io.rs2Val := rs2Val

  /** NOTE: Input from FetchStage */
  val ioif = io.in.bits
  iDec.io.inst  := ioif.inst
  iDec.io.pc    := ioif.pc

  /** NOTE: To ExecuteStage */
  val ioex = io.out.bits
  ioex.imm    := iDec.io.imm
  ioex.rs1V   := rs1Val
  ioex.rs2V   := rs2Val
  ioex.aluOp  := iDec.io.aluOp
  ioex.aluSel := iDec.io.aluSel
  ioex.memOp  := iDec.io.memAcc
  ioex.brAbs  := iDec.io.brAbs
  ioex.aluEn  := iDec.io.aluEn

  val iofw = ioex.foward
  iofw.gprRd  := iDec.io.rd
  iofw.csrRd  := iDec.io.csriw
  iofw.gprWE  := iDec.io.gprWE
  iofw.csrWE  := iDec.io.csrWE
  iofw.wbSel  := iDec.io.wbSel
  iofw.ebreak := iDec.io.ebreak
  iofw.ecall  := iDec.io.ecall
  iofw.pc     := ioif.pc
  iofw.csrVal := csrVal

  /** NOTE: To WriteBack */
  val iobk = io.toFetch.bits
  iobk.brDel  := iDec.io.imm
  iobk.brRel  := iDec.io.brRel
}

