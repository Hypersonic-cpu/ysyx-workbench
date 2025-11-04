package rvProc

import chisel3._
import chisel3.util._
// import chisel3.util.experimental.loadMemoryFromFileInline
// import firrtl.annotations.MemoryLoadFileType

object PATH {
  val dpicPath = "/mnt/hgfs/Arch-PA/ysyx-workbench/npc/rvproc/dpic/"
  def dpic(s: String) = java.nio.file.Paths.get(dpicPath, s).toString()
}

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

object ITYPE extends ChiselEnum {
  val tR, tI, tS, tB, tU, tJ, tN = Value
}

class BrCmpBundle extends Bundle {
  val beq = Bool()
  val blt = Bool()
}

class PcJmpBundle extends Bundle {
  val jIfeq = Bool()
  val jIfne = Bool()
  val jIflt = Bool()
  val jIfge = Bool()
  val jUncond = Bool()
}

class AluSelBundle extends Bundle {
  val rs1SelPC  = Bool()
  val rs2SelImm = Bool()
  // NOTE: This field also represents SRA
  val rs2Invert = Bool()
}

object InstOp extends ChiselEnum {
  val Load   = Value(0b00000.U)
  val OpImm  = Value(0b00100.U)
  val Auipc  = Value(0b00101.U)
  val Store  = Value(0b01000.U)
  val OpReg  = Value(0b01100.U)
  // val OpFP   = Value(0b10100.U)
  val Lui    = Value(0b01101.U)
  // val Branch = Value(0b11000.U)
  val Jalr   = Value(0b11001.U)
  val System = Value(0b11100.U)
  // val Jal    = Value(0b11011.U)
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

object MemLenOp extends ChiselEnum {
  val Byte = Value(0b00.U)
  val Half = Value(0b01.U)
  val Word = Value(0b10.U)
  val None = Value(0b11.U)
}

class MemAccBundle extends Bundle {
  // Whether enable mem access is ctrl by 
  // lenOp =?= None
  val lenOp = MemLenOp()
  val sExt  = Bool()
  val isLd  = Bool()
}

object WbSrcOp extends ChiselEnum {
  val fromAlu, fromPC, fromMem = Value
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
  io.regPrb := Mux(io.rsPin.orR, regs(io.rsPin), 0.U)

  printf(cf"<<REG>> R[${io.rs1}] = ${io.rs1V}%x\n")
  printf(cf"<<REG>> R[${io.rs2}] = ${io.rs2V}%x\n")
  when (io.wrEn && io.rd.orR) {
    regs(io.rd) := io.data
  }
}

/** NOTE: 3 Nov 2025
  *  放弃把 Control 单独放在一个 unit 的想法. 因为
  *  Ctrl 仍然需要输入 inst, 不能直接获得 IDU 的输出.
  *  所以把 Ctrl 集成进入 IDU 更加合适. pcSel 由 WBU
  *  根据 pcJmp 和 branch result 生成.
  */

class IDU extends Module {
  val io = IO(new Bundle {
    val inst   = Input(Tp.InstType())
    val rs1    = Output(Tp.RegIdxType())
    val rs2    = Output(Tp.RegIdxType())
    val rd     = Output(Tp.RegIdxType())
    val imm    = Output(Tp.RegType())
    val regWr  = Output(Bool())
    val memAcc = Output(new MemAccBundle())
    val aluOp  = Output(IntAluOp())
    val aluSel = Output(new AluSelBundle())
    val pcJmp  = Output(new PcJmpBundle())
    val wbSel  = Output(WbSrcOp())
    val ebreak = Output(Bool())
  })

  val opcode = io.inst(6, 0)
  val funct3 = io.inst(14, 12)
  val funct7 = io.inst(31, 25)
  val rvBase  = opcode(1, 0) === 0b11.U(2.W)
  assert(rvBase, cf"Inst[1:0] is not 0b11: opcode=${opcode}%x")

  val (opName, opValid) = InstOp.safe(opcode(6, 2))
  assert(opValid, cf"Invalid opcode encountered: opcode=${opcode}%x")
  // val isEbreak = sysOp && io.inst(20)
  // val isEcall  = sysOp && (~io.inst(20))
  val isEbreak = opName === InstOp.System && io.inst(20)
  io.ebreak := isEbreak

  // On ECALL we prepare reg a0 (x10)
  io.rs1    := MuxCase(io.inst(19, 15), Seq(
    isEbreak                -> 10.U,
    (opName === InstOp.Lui) -> 0.U
  ))
  // (isEbreak, 10.U, io.inst(19, 15))
  io.rs2    := io.inst(24, 20)
  io.rd     := io.inst(11,  7)
  val immIS  = io.inst(31, 20).asSInt.pad(32).asUInt
  val immIU  = io.inst(31, 20).pad(32)
  val immU   = io.inst(31, 12) << 12

  // TODO:
  val instTp  = MuxLookup(opName, ITYPE.tN) ( Seq(
    InstOp.OpImm  -> ITYPE.tI,
    InstOp.OpReg  -> ITYPE.tR,
    InstOp.Jalr   -> ITYPE.tI,
    InstOp.Lui    -> ITYPE.tU,
    InstOp.Auipc  -> ITYPE.tU,
    InstOp.Load   -> ITYPE.tI,
    InstOp.Store  -> ITYPE.tS,
    InstOp.System -> ITYPE.tN
    ))
  io.aluOp := Mux(
    opName === InstOp.OpReg || opName === InstOp.OpImm,
    IntAluOp(funct3), IntAluOp.Add
  )
  io.aluSel.rs2Invert := funct7(5).asBool
  io.aluSel.rs2SelImm := ~(instTp === ITYPE.tN || instTp === ITYPE.tR)
  io.aluSel.rs1SelPC  := false.B // TODO: JAL

  // TODO: SEXT
  io.imm    := MuxLookup(instTp, 0.U) (Seq(
    ITYPE.tI -> Mux(true.B, immIS, immIU), 
    ITYPE.tU -> immU
  ))

  io.memAcc.lenOp := MemLenOp(Mux(
    opName === InstOp.Load || opName === InstOp.Store,
    funct3(1, 0), 0b11.U
  ))
  io.memAcc.isLd := opName === InstOp.Load 
  io.memAcc.sExt := ~funct3(2)

  io.regWr  := ~(
    instTp === ITYPE.tN || 
    instTp === ITYPE.tB || 
    instTp === ITYPE.tS)

  io.pcJmp.jIfeq   := false.B
  io.pcJmp.jIfne   := false.B
  io.pcJmp.jIflt   := false.B
  io.pcJmp.jIfge   := false.B
  // TODO: JAL
  io.pcJmp.jUncond := opName === InstOp.Jalr

  io.wbSel := MuxCase(WbSrcOp.fromAlu, Seq(
    (opName === InstOp.Jalr) -> WbSrcOp.fromPC,
    (opName === InstOp.Load) -> WbSrcOp.fromMem
  ))

  printf(cf"Decode: inst ${io.inst}%x type${instTp} alu${io.aluOp} " + 
    cf"wr[M|R] = ${io.memAcc.lenOp}|${io.regWr} jmp ${io.pcJmp.jUncond}\n")
  printf(cf"\trs1 ${io.rs1}%d, rs2 ${io.rs2}%d, imm ${io.imm}%x\n");

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

  printf(cf"\trs1V ${io.rs1V}%x, rs2V ${io.rs2V}%x, imm ${io.imm}%x\n");
  io.res := 0.U
  io.brCmp.beq := false.B
  io.brCmp.blt := false.B
  val src1 = Mux(io.sel.rs1SelPC, io.pc, io.rs1V)
  val src2 = Mux(io.sel.rs2SelImm, io.imm, io.rs2V)
  switch (io.op) {
    is (IntAluOp.Add) {
      io.res := src1 + src2
    }
  }
  printf(cf"\t${src1}%x op ${src2}%x = ${io.res}%x\n")
}

/**
  * NOTE: 内置了 iMem 为了防止 memory 接口暴露在顶层.
  */
class LSU extends Module {
  val io = IO(new Bundle {
    val pcin   = Input(Tp.PCType())
    val addr   = Input(Tp.AddrType())
    val data   = Input(Tp.RegType())
    val memAcc = Input(new MemAccBundle())
    val load   = Output(Tp.RegType())
    val inst   = Output(Tp.InstType())
  })

  val iMem = Module(new PMemBox())
  val lenOp = io.memAcc.lenOp
  iMem.io.clock := clock
  iMem.io.reset := reset
  iMem.io.pcin  := io.pcin
  iMem.io.addr  := io.addr
  iMem.io.data  := io.data
  iMem.io.byteMask := MuxLookup(lenOp, 0.U) (
    Seq(
      MemLenOp.Byte -> (0x1.U << (io.addr(1, 0) << 3.U)),
      MemLenOp.Half -> (0x3.U << (io.addr(1, 1) << 4.U)),
      MemLenOp.Word -> 0xf.U
    )
  )
  iMem.io.memEn := lenOp =/= MemLenOp.None
  // Load and store should not happen together
  iMem.io.wrEn  := ~io.memAcc.isLd

  val lraw = iMem.io.loadRaw >> (io.addr(1, 0) << 3)
  val sext = io.memAcc.sExt
  printf(cf"DPI Chisel Raw ${lraw}%x SEXT ${sext}\n")
  io.inst := iMem.io.instRaw
  io.load := MuxLookup(lenOp, 0.U) (Seq(
    MemLenOp.Byte -> Mux(sext, lraw(7, 0).asSInt.pad(32).asUInt, lraw(7, 0)),
    MemLenOp.Half -> Mux(sext, lraw(15, 0).asSInt.pad(32).asUInt, lraw(15, 0)),
    MemLenOp.Word -> lraw
    )
  )
}

// MUX, Write data selection
class WBU extends Module {
  val io = IO(new Bundle {
    val brCmp = Input(new BrCmpBundle())
    val pcJmp = Input(new PcJmpBundle())
    val wbSel = Input(WbSrcOp())
    val pc    = Input(Tp.PCType())
    val aluV  = Input(Tp.RegType())
    val memV  = Input(Tp.RegType())
    val nxpc  = Output(Tp.PCType())
    val data  = Output(Tp.RegType())
  })
  val jmp = io.pcJmp.jUncond
  val snpc = io.pc + 4.U
  io.nxpc := Mux(jmp, io.aluV, snpc)
  // NOTE: Once PC jumps, try store its next pc
  // For B-type insts, wrEn had been set to false.
  // FIXME: 目前的思路: 需要存储PC的Jmp(Link)不可能
  // 是有条件的, 所以RegWB不需要考虑branch.
  io.data := MuxLookup(io.wbSel, 0.U) (Seq(
    WbSrcOp.fromAlu -> io.aluV, 
    WbSrcOp.fromMem -> io.memV,
    WbSrcOp.fromPC  -> snpc
  ))
}

class rvCore() extends Module {
  val io = IO(new Bundle{
    val regPin  = Input(Tp.RegIdxType())
    val regPrb  = Output(Tp.RegType())
    val outPC   = Output(Tp.PCType())
  })

  // State
  val pc     = RegInit(0.U(ISA.PCBits.W))
  val iReg   = Module(new RegFile())

  printf(cf"[ PC = ${pc}%x ]\n")
  // Func
  val iDec   = Module(new IDU())
  val iExe   = Module(new EXU()) 
  val iLsu   = Module(new LSU())
  val iWrite = Module(new WBU())
  val iEcall = Module(new EcallBox())

  // Probing 
  io.outPC := pc 
  iReg.io.rsPin := io.regPin 
  io.regPrb := iReg.io.regPrb

  // IFU in
  iLsu.io.pcin := pc
  // IFU out
  val inst = iLsu.io.inst

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
  iExe.io.rs2V := rs2V
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
  iLsu.io.memAcc := iDec.io.memAcc
  // LSU out
  val loadV = iLsu.io.load
  printf(cf"DPI Chisel loadval ${loadV}%x\n")

  // WB in
  iWrite.io.brCmp := br
  iWrite.io.pc   := pc
  iWrite.io.aluV := res
  iWrite.io.memV := loadV
  iWrite.io.pcJmp := iDec.io.pcJmp
  iWrite.io.wbSel := iDec.io.wbSel
  // WB out 
  pc           := iWrite.io.nxpc
  iReg.io.data := iWrite.io.data

  printf(cf"<<<WB>>> rd ${iReg.io.rd} data ${iReg.io.data}%x\n")

  // printf(cf"   R[${iDec.io.rs1}%d]=0x${rs1V}%x R[${iDec.io.rs2}%d]=0x${rs2V}%x "
  //     + cf"Alu=${sAlu.io.sum}%x Eq=${sAlu.io.isEq}\n")

  iEcall.io.clock := this.clock
  iEcall.io.reset := this.reset
  iEcall.io.pcin  := pc
  iEcall.io.a0in  := rs1V
  iEcall.io.isEbreak := iDec.io.ebreak
  iEcall.io.isEcall  := false.B

  dontTouch(iWrite.io)
  dontTouch(iDec.io)
  dontTouch(iExe.io)
  dontTouch(iLsu.io)
}
