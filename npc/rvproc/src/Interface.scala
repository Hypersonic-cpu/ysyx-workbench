package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert

class FetchToDecode extends Bundle {
  val pc     = Tp.RegType()
  val inst   = Tp.RegType()
}

class RegFromIDU extends Bundle {
  val rs1  = Tp.RegIdxType()
  val rs2  = Tp.RegIdxType()
  val csrr = Tp.CsrIdxType()
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
  val Add  = Value(0b000.U)
  val Sll  = Value(0b001.U) // Shift left
  val Slt  = Value(0b010.U)
  val Sltu = Value(0b011.U)
  val Xor  = Value(0b100.U)
  val Srr  = Value(0b101.U) // Shift right
  val Or   = Value(0b110.U)
  val And  = Value(0b111.U)
}

class AluSel extends Bundle {
  val rs1SelPC  = Bool()
  val rs2SelImm = Bool()
  // NOTE: This field also represents SRA
  val rs2Invert = Bool()
  val rs1Invert = Bool()
  val outSelCsr = Bool()
  // val saveCmp   = Bool()
  // val cmpImm    = Bool()
}

class BrCmp extends Bundle {
  val beq  = Bool()
  val bltu = Bool()
  val blts = Bool()
}

class BrJmp extends Bundle {
  val bIfeq = Bool()
  val bIfne = Bool()
  val bIflt = Bool()
  val bIfge = Bool()
  // def isBr = bIfeq && bIfne && bIflt && bIfge
  // val bEnable = Bool()
  // val jUncond = Bool()
}

object MemLen extends ChiselEnum {
  val Byte = Value(0b00.U)
  val Half = Value(0b01.U)
  val Word = Value(0b10.U)
  val None = Value(0b11.U)
}

class MemOp extends Bundle {
  val len = MemLen()
  val sExt  = Bool()
  val isSt  = Bool()
  def isEn  = { len === MemLen.None }
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

class DecodeBackward extends Bundle {
  val brRel  = Bool()
  val brDel  = Tp.RegType()
}

class ExecuteBackward extends Bundle {
  val brAbs  = Bool()
  val brVal  = Tp.RegType()
}

class DecodeFoward extends Bundle {
  val wbSel  = WbSel()
  val gprRd  = Tp.RegIdxType()
  val gprWE  = Bool()
  val csrRd  = Tp.CsrIdxType()
  val csrWE  = Bool()
  val ebreak = Bool()
  val ecall  = Bool()
  // PC is debug only...
  val pc     = Tp.RegType()
  val csrVal = Tp.RegType()
}

class DecodeToExecute extends Bundle {
  val rs1V   = Tp.RegType()
  val rs2V   = Tp.RegType()
  val imm    = Tp.RegType()
  val aluOp  = AluOp()
  val aluSel = new AluSel()
  val brAbs  = Bool()

  val memOp  = new MemOp()
  val aluEn  = Bool()
  // val memEn  = Bool()

  val foward = new DecodeFoward()
}

class ExecuteToMemory extends Bundle {
  val memOp  = new MemOp()
  // val takeBr = Bool()
  val aluOut = Tp.RegType()
  val rs2Val = Tp.RegType()

  val foward = new DecodeFoward()
}

class MemoryToWrBack extends Bundle {
  val aluOut = Tp.RegType()
  val lsuOut = Tp.RegType()

  val foward = new DecodeFoward()
}

