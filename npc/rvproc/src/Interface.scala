package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert

import chisel3._

class FetchToDecode extends Bundle {
  val pc         = Tp.RegType()
  val inst       = Tp.RegType()
  val predTaken  = Bool()
  val predTarget = Tp.AddrType()
  val predBtbHit = Bool()
}

class RegFromIDU extends Bundle {
  val rs1   = Tp.RegIdxType()
  val rs2   = Tp.RegIdxType()
  val csrr  = Tp.CsrIdxType()
  val ecall = Bool()
}

class RegToIDU extends Bundle {
  val rs1Val = Tp.RegType()
  val rs2Val = Tp.RegType()
  val csrVal = Tp.RegType()
}

class RegFromWBU extends Bundle {
  val gprRd = Tp.RegIdxType()
  val gprWE = Bool()
  val gprIn = Tp.RegType()

  val csrRd = Tp.CsrIdxType()
  val csrWE = Bool()
  val csrIn = Tp.RegType()
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
  val brRel      = Bool()
  val brDel      = Tp.RegType()
  val brAbs      = Bool()
  val brVal      = Tp.RegType()
  val brLPC      = Tp.AddrType()
  val isBr       = Bool()
  val mispred    = Bool()
  val predBtbHit = Bool()
  def take       = brRel || brAbs
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
  val csrVal = Tp.RegType()
  // PC is debug only...
  val pc     = UInt((if (GlbCtrl.debug) 32 else 0).W)
  val inst   = UInt((if (GlbCtrl.debug) 32 else 0).W)

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
  val predTarget = Tp.AddrType()
  val predBtbHit = Bool()
  val foward     = new DecodeFoward
}

class ExecuteToMemory extends Bundle {
  val memOp  = new MemOp()
  // val takeBr = Bool()
  val aluOut = Tp.RegType()
  val rs2Val = Tp.RegType()

  val foward = new DecodeFoward
}

class MemoryToWrBack extends Bundle {
  val aluOut = Tp.RegType()
  val lsuOut = Tp.RegType()

  val foward = new DecodeFoward
}

// class InstCommit extends Bundle {}
