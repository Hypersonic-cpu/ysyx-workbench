package sCPU

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

object sISA {
  val InstLen = 8
  val RegLen = 8
  val PCLen = 4
  val RegNum = 4
  val RegIdx = 2
}

object WrSource extends ChiselEnum {
  val FromImm, FromAlu = Value
}

object PcSource extends ChiselEnum {
  val FromJmp, FromPC = Value
}

class sDecode extends Module {
  val io = IO(new Bundle {
    val inst = Input(UInt(sISA.InstLen.W))
    val rs1  = Output(UInt(sISA.RegIdx.W))
    val rs2  = Output(UInt(sISA.RegIdx.W))
    val rd   = Output(UInt(sISA.RegIdx.W))
    val imm  = Output(UInt(sISA.RegLen.W))
    val wrs  = Output(WrSource())
    val jmp  = Output(PcSource())
    val wren = Output(Bool())
    val disp = Output(Bool())
  })

  io.disp := false.B
  io.wren := false.B
  io.jmp  := PcSource.FromPC
  io.wrs  := WrSource.FromAlu // Should not use default val
  io.imm  := 0.U
  /** [opcode : rd : rs1 : rs2] */
  io.rs1  := io.inst(3, 2)
  io.rs2  := io.inst(1, 0)
  io.rd   := io.inst(5, 4)

  switch (io.inst(7, 6)) {
    is (0x00.U) {
      // add 
      io.wren := true.B
      io.wrs  := WrSource.FromAlu
    }
    is (0x01.U) {
      // out 
      io.disp := true.B
    }
    is (0x10.U) {
      // li
      io.imm := io.inst(3, 0)
      io.wren := true.B
      io.wrs  := WrSource.FromImm
    }
    is (0x11.U) {
      // bner0
      io.rs1 := 0.U
      io.imm := io.inst(5, 2)
      io.jmp := PcSource.FromJmp
    }
  }
}

class sRegFile extends Module {
  val io = IO(new Bundle {
    val idx1 = Input(UInt(sISA.RegIdx.W))
    val idx2 = Input(UInt(sISA.RegIdx.W))
    val idxW = Input(UInt(sISA.RegIdx.W))
    val datW = Input(UInt(sISA.RegLen.W))
    val wrEn = Input(Bool())
    val rs1V = Output(UInt(sISA.RegLen.W))
    val rs2V = Output(UInt(sISA.RegLen.W))
  })

  val regs = Reg(Vec(sISA.RegNum, UInt(sISA.RegLen.W)))
  io.rs1V := regs(io.idx1)
  io.rs2V := regs(io.idx2)

  when (io.wrEn) {
    regs(io.idxW) := io.datW
  }
}

class sAlu extends Module {
  val io = IO(new Bundle {
    val rs1V = Input(UInt(sISA.RegLen.W))
    val rs2V = Input(UInt(sISA.RegLen.W))
    val sum  = Output(UInt(sISA.RegLen.W))
    val isEq = Output(Bool())
  })
  io.sum := io.rs1V + io.rs2V 
  io.isEq := io.rs1V === io.rs2V
}

class sCPU(romFile: String) extends Module {
  val io = IO(new Bundle{
    val dispVal = Output(UInt(sISA.RegLen.W))
    val dispEna = Output(Bool())
  })

  val pc    = RegInit(0.U(sISA.PCLen.W))
  val iROM  = Mem((1 << sISA.PCLen), UInt(sISA.InstLen.W))
  loadMemoryFromFileInline(iROM, romFile, MemoryLoadFileType.Hex)

  val readInst  = iROM.read(pc)
  val iDec  = Module(new sDecode())
  iDec.io.inst := readInst

  printf(cf"=> PC 0x$pc%x, inst $readInst%b\n")

  val sReg  = Module(new sRegFile())
  
  sReg.io.idx1 := iDec.io.rs1
  sReg.io.idx2 := iDec.io.rs2
  sReg.io.idxW := iDec.io.rd
  val rs1V  = sReg.io.rs1V
  val rs2V  = sReg.io.rs2V
  val immV  = iDec.io.imm

  val sAlu = Module(new sAlu())
  sAlu.io.rs1V := rs1V 
  sAlu.io.rs2V := rs2V 

  printf(cf"   R[${iDec.io.rs1}%d]=0x${rs1V}%x R[${iDec.io.rs2}%d]=0x${rs2V}%x "
      + cf"Alu=${sAlu.io.sum}%x Eq=${sAlu.io.isEq}\n")
  
  sReg.io.datW := Mux(iDec.io.wrs === WrSource.FromImm, 
    immV, sAlu.io.sum)
  sReg.io.wrEn := iDec.io.wren
  printf(cf"   R[${iDec.io.rd}%d] <- 0x${sReg.io.datW}%x\n")

  pc := Mux(
    (iDec.io.jmp === PcSource.FromJmp) & sAlu.io.isEq, 
    rs2V, pc + 1.U)

  io.dispEna := iDec.io.disp
  io.dispVal := rs2V
}
