package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert

import chisel3._

class FetchToDecode extends Bundle {
  val pc           = Tp.RegType()
  val inst         = Tp.RegType()
  val predTaken    = Bool()
  val predTarget   = Tp.AddrType()
  val predBtbHit   = Bool()
  val predBhtCnt   = UInt(2.W)
  val ifuExcp      = Bool()
  val ifuExcpCause = UInt(4.W)
}

class RegFromIDU extends Bundle {
  val rs1  = Tp.RegIdxType()
  val rs2  = Tp.RegIdxType()
  val csrr = Tp.CsrIdxType()
}

class RegToIDU extends Bundle {
  val rs1Val   = Tp.RegType()
  val rs2Val   = Tp.RegType()
  val csrVal   = Tp.RegType()
  val mtvecVal = Tp.RegType()
}

class RegFromWBU extends Bundle {
  val gprRd = Tp.RegIdxType()
  val gprWE = Bool()
  val gprIn = Tp.RegType()

  val csrRd = Tp.CsrIdxType()
  val csrWE = Bool()
  val csrIn = Tp.RegType()

  val excpValid = Bool()
  val excpPC    = Tp.AddrType()
  val excpCause = UInt(4.W)
}

object AluOp extends ChiselEnum {
  val Add  = Value("b000".U)
  val Sll  = Value("b001".U) // Shift left
  val Slt  = Value("b010".U)
  val Sltu = Value("b011".U)
  val Xor  = Value("b100".U)
  val Srr  = Value("b101".U) // Shift right
  val Or   = Value("b110".U)
  val And  = Value("b111".U)
}

/** M-extension operation, encoded as funct3[2:0]. */
object MulDivOp extends ChiselEnum {
  val Mul    = Value("b000".U)
  val Mulh   = Value("b001".U)
  val Mulhsu = Value("b010".U)
  val Mulhu  = Value("b011".U)
  val Div    = Value("b100".U)
  val Divu   = Value("b101".U)
  val Rem    = Value("b110".U)
  val Remu   = Value("b111".U)
}

class AluSel extends Bundle {
  val rs1SelPC  = Bool()
  val rs2SelImm = Bool()
  val rs2Invert = Bool()
  val rs1Invert = Bool()
  val brSelCsr  = Bool()
  val shArith   = Bool()
  val cmpUsgn   = Bool()
  // val saveCmp   = Bool()
  // val cmpImm    = Bool()
}

class BrCmp extends Bundle {
  val beq  = Bool()
  val bltu = Bool()
  val blts = Bool()
}

class BrInst extends Bundle {
  val bIfeq = Bool()
  val bIfne = Bool()
  val bIflt = Bool()
  val bIfge = Bool()
  // val bUsgn = Bool()
  val isAbs = Bool()
  val isBr  = Bool()
  // def isBr = bIfeq || bIfne || bIflt || bIfge
}

object MemLen extends ChiselEnum {
  val Byte = Value("b00".U)
  val Half = Value("b01".U)
  val Word = Value("b10".U)
  val None = Value("b11".U)
}

class MemOp extends Bundle {
  val len  = MemLen()
  val sExt = Bool()
  val isSt = Bool()
  def isEn = { len =/= MemLen.None }
}

object WbSel extends ChiselEnum {
  val fromAlu, fromPC, fromMem, fromCsr = Value
}

// object BrType extends ChiselEnum {
//   val rel, abs = Value
// }

// object BrSel extends ChiselEnum {
//   val fromAlu, fromCsr = Value
// }

// class DecodeBackward extends Bundle {
// }

class ExecuteBackward extends Bundle {
  val brTaken    = Bool()
  val brTarget   = Tp.AddrType()
  val brLPC      = Tp.AddrType()
  val brLPC4     = Tp.AddrType() // pre-computed brLPC + 4
  val isBr       = Bool()
  val mispred    = Bool()
  val predBtbHit = Bool()
  val predBhtCnt = UInt(2.W)
  val isCall     = Bool()
  val isRet      = Bool()
}

object StallCause extends ChiselEnum {
  val NoStall, InstFetch, LoadStore, Branch, RAW = Value
}

class DecodeFoward extends Bundle {
  val wbSel  = WbSel()
  val gprRd  = Tp.RegIdxType()
  val gprWE  = Bool()
  val csrRd  = Tp.CsrIdxType()
  val csrWE  = Bool()
  val ebreak = Bool()
  val ecall  = Bool()
  val fenceI = Bool()
  val csrVal = Tp.RegType()
  val mcause = UInt(4.W)
  val pc     = Tp.AddrType()
  val snpc   = Tp.AddrType()
  val inst   = UInt((if (GlbCtrl.debug) 32 else 0).W)

  val excpValid = Bool()
  val excpFlush = Bool()

  // Removed by compiler when not debugging.
  val stallT = StallCause()
}

class DecodeToExecute extends Bundle {
  val rs1V       = Tp.RegType()
  val rs2V       = Tp.RegType()
  val imm        = Tp.RegType()
  val pc         = Tp.AddrType()
  val aluOp      = AluOp()
  val aluSel     = new AluSel
  val brInst     = new BrInst
  val memOp      = new MemOp
  val aluEn      = Bool()
  val predTaken  = Bool()
  val predTarget = Tp.AddrType() // TODO: remove
  val predBtbHit = Bool() // TODO: remove fields
  val predBhtCnt = UInt(2.W) // TODO: remove
  val isCall     = Bool()
  val isRet      = Bool()
  val isMul      = Bool()
  val isDiv      = Bool()
  val mulDivOp   = MulDivOp()
  val foward     = new DecodeFoward
}

class ExecuteToMemory extends Bundle {
  val memOp   = new MemOp()
  val isMemEn = Bool()
  val aluOut  = Tp.RegType()
  val rs2Val  = Tp.RegType()

  val foward = new DecodeFoward
}

class MemoryToWrBack extends Bundle {
  val aluOut = Tp.RegType()
  val lsuOut = Tp.RegType()

  val foward = new DecodeFoward
}

class IntAluIn extends Bundle {
  val rs1V       = Tp.RegType()
  val rs2V       = Tp.RegType()
  val imm        = Tp.RegType()
  val pc         = Tp.AddrType()
  val aluOp      = AluOp()
  val aluSel     = new AluSel
  val brInst     = new BrInst
  val memOp      = new MemOp
  val aluEn      = Bool()
  val predTaken  = Bool()
  val predTarget = Tp.AddrType()
  val predBtbHit = Bool()
  val predBhtCnt = UInt(2.W)
  val isCall     = Bool()
  val isRet      = Bool()
  val foward     = new DecodeFoward
}

class IntMulIn extends Bundle {
  val rs1    = Tp.RegType()
  val rs2    = Tp.RegType()
  val op     = MulDivOp()
  val foward = new DecodeFoward
}

class IntMulOut extends Bundle {
  val result = Tp.RegType()
  val foward = new DecodeFoward
}

class IntDivIn extends Bundle {
  val rs1    = Tp.RegType()
  val rs2    = Tp.RegType()
  val op     = MulDivOp()
  val foward = new DecodeFoward
}

class IntDivOut extends Bundle {
  val result = Tp.RegType()
  val foward = new DecodeFoward
}
