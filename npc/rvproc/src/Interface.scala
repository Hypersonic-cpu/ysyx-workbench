package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert

class WrBackToFetch extends Bundle {
  val npc    = Tp.RegType()
  val takeBr = Bool()
}

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
  val rd   = Tp.RegIdxType()
  val gpre = Bool()
  val gprw = Tp.RegType()
  val csre = Bool()
  val csrw = Tp.CsrIdxType()
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
  // NOTE: True if cmp result saved to reg
  val saveCmp   = Bool()
}

class BrCmp extends Bundle {
  val beq = Bool()
  val blt = Bool()
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

class DecodeFoward extends Bundle {
  val wbSel  = Bool()
  val rd     = Tp.RegIdxType()
  val gprWE  = Bool()
  // val csrw   = Tp.CsrIdxType()
  val csrWE  = Bool()
  // val wbMode = Bool()
  val ebreak = Bool()
  val ecall  = Bool()
  val pc     = Tp.RegType()
  val csrVal = Tp.RegType()
}

class DecodeToExecute extends Bundle {
  val rs1V   = Tp.RegType()
  val rs2V   = Tp.RegType()
  val imm    = Tp.RegType()
  val aluOp  = AluOp()
  val aluSel = new AluSel()
  val brJmp  = new BrJmp()
  val memOp  = new MemOp()

  val foward = new DecodeFoward()
}

class ExecuteToMemory extends Bundle {
  val memOp  = new MemOp()
  val takeBr = Bool()
  val aluOut = Tp.RegType()
  val rs2Val = Tp.RegType()

  val foward = new DecodeFoward()
}

class MemoryToWrBack extends Bundle {
  val takeBr = Bool()
  val aluOut = Tp.RegType()
  val lsuOut = Tp.RegType()

  val foward = new DecodeFoward()
}

