package rvProc

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import firrtl.annotations.MemoryLoadFileType

// class sROM(size: Int, paBits: Int) extends Module {
//   val io = IO(new Module {
//     val byteAddr = Input(UInt(paBits.W))
//     val 
//   })
//
// }

/** TODO:
  * 需要把 Control 单独拿出来吗? UCB 的课件看起来
  * 比自己画的好看.
  */

object ISA {
  val InstBits    = 32
  val RegBits     = 32
  val PCBits      = 32
  val RegNum      = 16
  val RegIdxBits  =  4
  val AddrBits    = 32
}

object Tp {
  def PCType() = UInt(ISA.PCBits.W)
  def RegType() = UInt(ISA.RegBits.W)
  def InstType() = UInt(ISA.InstBits.W)
  def RegIdxType() = UInt(ISA.RegIdxBits.W)
  // Now it equals RegType() so no padding is needed.
  def AddrType() = UInt(ISA.AddrBits.W)
}

class BrCmpBundle extends Bundle {
  val brEq = Bool()
  val brUn = Bool() 
  val brLt = Bool()
}

class AluSelBundle extends Bundle {
  val rs1SelPC  = Bool()
  val rs2SelImm = Bool()
  val rs2Invert = Bool()
}

class PcSelBundle extends Bundle {
  val jmpEq = Bool()
}

object IntAluOp extends ChiselEnum {
  val Add  = Value(0b000.U)
  val Sll  = Value(0b001.U) // Shift left
  val Slt  = Value(0b010.U)
  val Sltu = Value(0b011.U)
  val Xor  = Value(0b100.U)
  val Srr  = Value(0b101.U) // Shift right
  val Or   = Value(0b110.U)
  val And  = Value(0b111.U)
}

object WbSource extends ChiselEnum {
  val FromImm, FromAlu = Value
}

object PcSource extends ChiselEnum {
  val FromJmp, FromPC = Value
}

object Rs1Source extends ChiselEnum {
  val FromPC, FromRs1 = Value
}

class RegFile extends Module {
  val io = IO(new Bundle {
    val rs1  = Input(Tp.RegIdxType())
    val rs2  = Input(Tp.RegIdxType())
    val rd   = Input(Tp.RegIdxType())
    val data = Input(Tp.RegType())
    val wrEn = Input(Bool())
    val rs1V = Output(Tp.RegType())
    val rs2V = Output(Tp.RegType())

    val rsPin   = Input(Tp.RegIdxType())
    val regPrb  = Output(Tp.RegType())
  })

  val regs = Reg(Vec(ISA.RegNum, Tp.RegType()))

  io.rs1V := Mux(io.rs1.orR, regs(io.rs1), 0.U)
  io.rs2V := Mux(io.rs2.orR, regs(io.rs2), 0.U)
  io.regPrb := Mux(io.rsPin.orR, regs(io.regPrb), 0.U)

  when (io.wrEn && io.rd.orR) {
    regs(io.rd) := io.data
  }
}

/** Decoder, NOT responsible for read register */
class IDU extends Module {
  val io = IO(new Bundle {
    val inst = Input(Tp.InstType())
    val rs1  = Output(Tp.RegIdxType())
    val rs2  = Output(Tp.RegIdxType())
    val rd   = Output(Tp.RegIdxType())
    val imm  = Output(Tp.RegType())
    // val wrs  = Output(WrSource())
    // val jmp  = Output(PcSource())
    val regWr = Output(Bool())
    val memWr = Output(Bool())
    val aluOp = Output(IntAluOp())
    val aluSel = Output(new AluSelBundle())
  })

  val opcode = io.inst(6, 0)
  val funct3 = io.inst(14, 12)
  val funct7 = io.inst(31, 25)
  val rvBase  = opcode === 0b11.U(2.W)
  val arithOp = opcode === 0b100.U(3.W)
  io.aluOp  := IntAluOp(funct3)
  io.aluSel.rs2Invert := funct7(5).asBool
  io.aluSel.rs2SelImm := true.B
  io.aluSel.rs1SelPC  := false.B

  io.rs1    := io.inst(19, 15)
  io.rs2    := io.inst(24, 20)
  io.rd     := io.inst(11,  7)
  io.imm    := io.inst(31, 20)
  io.memWr  := false.B
  io.regWr  := true.B

  printf(cf"Decode: inst ${io.inst}%x alu${io.aluOp} " + 
    cf"wr[M|W] = ${io.memWr}|${io.regWr}\n")
}

class EXU extends Module {
  val io = IO(new Bundle {
    val rs1V = Input(Tp.RegType())
    val rs2V = Input(Tp.RegType())
    val pc   = Input(Tp.PCType())
    val imm  = Input(Tp.RegType())
    val sel  = Input(new AluSelBundle())
    val op   = Input(IntAluOp())
    val res  = Output(Tp.RegType())
    val brCmp = Output(new BrCmpBundle())
  })
  io.res := 0.U
  io.brCmp.brEq := false.B
  io.brCmp.brUn := false.B
  io.brCmp.brLt := false.B
  val src1 = Mux(io.sel.rs1SelPC, io.pc, io.rs1V)
  val src2 = Mux(io.sel.rs2SelImm, io.imm, io.rs2V)
  switch (io.op) {
    is (IntAluOp.Add) {
      io.res := src1 + src2
    }
  }
}

class LSU extends Module {
  val io = IO(new Bundle {
    val addr   = Input(Tp.AddrType())
    val data   = Input(Tp.RegType())
    val ldEn   = Input(Bool())
    val wrEn   = Input(Bool())
    val load   = Output(Tp.RegType())
  })
  io.load := 0.U
}

// MUX, Write data selection
class WBU extends Module {
  val io = IO(new Bundle {
    val brCmp = Input(new BrCmpBundle())
    val pc    = Input(Tp.PCType())
    val aluV  = Input(Tp.RegType())
    val memV  = Input(Tp.RegType())
    val nxpc  = Output(Tp.PCType())
    val data  = Output(Tp.RegType())
  })
  io.nxpc := io.pc + 4.U
  io.data := io.aluV
}

class InstROM(romFile: String) extends Module {
  val io = IO(new Bundle{
    val pc   = Input(Tp.PCType())
    val inst = Output(Tp.InstType())
  })
  // TODO: How to correctly write combinatinal 'memory' ??
  val iROM  = Mem((1 << ISA.PCBits), Tp.InstType())
  loadMemoryFromFileInline(iROM, romFile, MemoryLoadFileType.Binary)
  io.inst := iROM.read(io.pc)
  printf(cf"[ PC = ${io.pc}%x ] inst = ${io.inst}%x\n")
}

class rvCore(romFile: String) extends Module {
  val io = IO(new Bundle{
    val regPin  = Input(Tp.RegIdxType())
    val regPrb  = Output(Tp.RegType())
    val outPC   = Output(Tp.PCType())
  })

  // State
  val pc     = RegInit(0.U(ISA.PCBits.W))
  val iReg   = Module(new RegFile())

  // Func
  val iFetch = Module(new InstROM(romFile))
  val iDec   = Module(new IDU())
  val iExe   = Module(new EXU()) 
  val iLsu   = Module(new LSU())
  val iWrite = Module(new WBU())

  // Probing 
  io.outPC := pc 
  iReg.io.rsPin := io.regPin 
  io.regPrb := iReg.io.regPrb

  // IFU in
  iFetch.io.pc := pc
  // IFU out
  val inst = iFetch.io.inst

  // IDU in
  iDec.io.inst := inst
  // IDU out 
  val rs1 = iDec.io.rs1
  val rs2 = iDec.io.rs2
  val imm = iDec.io.imm
  val op  = iDec.io.aluOp
  val sel = iDec.io.aluSel

  // Reg read 
  iReg.io.rs1 := rs1
  iReg.io.rs2 := rs2
  val rs1V = iReg.io.rs1V
  val rs2V = iReg.io.rs2V
  // Reg write
  iReg.io.rd := iDec.io.rd
  iReg.io.wrEn := iDec.io.regWr
  
  // EXU in
  iExe.io.rs1V := rs1V
  iExe.io.rs2V := rs1V
  iExe.io.imm  := imm 
  iExe.io.pc   := pc
  iExe.io.op   := op 
  iExe.io.sel  := sel
  // EXU out
  val res = iExe.io.res
  val br  = iExe.io.brCmp

  // LSU in
  // NOTE: No such inst that stores a calculated result.
  iLsu.io.addr := res
  iLsu.io.data := rs2V
  iLsu.io.wrEn := iDec.io.memWr
  // LSU out
  val loadV = iLsu.io.load

  // WB in
  iWrite.io.brCmp := br
  iWrite.io.pc   := pc
  iWrite.io.aluV := res
  iWrite.io.memV := loadV
  // WB out 
  pc := iWrite.io.nxpc
  iReg.io.data := iWrite.io.data

  // printf(cf"   R[${iDec.io.rs1}%d]=0x${rs1V}%x R[${iDec.io.rs2}%d]=0x${rs2V}%x "
  //     + cf"Alu=${sAlu.io.sum}%x Eq=${sAlu.io.isEq}\n")
}
