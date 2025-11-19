package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert
// import chisel3.util.experimental.loadMemoryFromFileInline
// import firrtl.annotations.MemoryLoadFileType

object PATH {
  val dpicPath = "/home/kong/ysyx-workbench/npc/rvproc/dpic/"
  def dpic(s: String) = java.nio.file.Paths.get(dpicPath, s).toString()
}

object ISA {
  val InstBits    = 32
  val RegBits     = 32
  val RegNum      = 16
  val RegIdxBits  =  4
  val CsrIdxBits  = 12
  val AddrBits    = 32
}

object Tp {
  def RegType() = UInt(ISA.RegBits.W)
  def InstType() = UInt(ISA.InstBits.W)
  def RegIdxType() = UInt(ISA.RegIdxBits.W)
  def CsrIdxType() = UInt(ISA.CsrIdxBits.W)
  // Now it equals RegType() so no padding is needed.
  def AddrType() = UInt(ISA.AddrBits.W)
}

object ITYPE extends ChiselEnum {
  val tR, tI, tS, tB, tU, tJ, tN, tX = Value
}

class BrCmpBundle extends Bundle {
  val beq = Bool()
  val blt = Bool()
}

class PcJmpBundle extends Bundle {
  val bIfeq = Bool()
  val bIfne = Bool()
  val bIflt = Bool()
  val bIfge = Bool()
  val bEnable = Bool()
  val jUncond = Bool()
  val jToCsr  = Bool()
}

class AluSelBundle extends Bundle {
  val rs1SelPC  = Bool()
  val rs2SelImm = Bool()
  // NOTE: This field also represents SRA
  val rs2Invert = Bool()
  val isBranch  = Bool()
}

object BitMath {
  implicit class UIntSignExtender(val i: UInt) extends AnyVal {
    def SExt(width: Int = ISA.RegBits): UInt = {
      i.asSInt.pad(width).asUInt
    }
    def MSBU(idx: Int = 0): UInt = {
      val chosen = ISA.RegBits-1-idx
      i(chosen, chosen)
    }
    def MSB(idx: Int = 0) = {
      val chosen = ISA.RegBits-1-idx
      i(chosen, chosen)
    }
  }
}
import BitMath._

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

object SysOp extends ChiselEnum {
  val ECall = Value(0b00.U)
  val CsrRW = Value(0b01.U)
  val CsrRS = Value(0b10.U)
  val CsrRC = Value(0b11.U)
}

class MemAccBundle extends Bundle {
  // Whether enable mem access is ctrl by 
  // lenOp =?= None
  val lenOp = MemLenOp()
  val sExt  = Bool()
  val isSt  = Bool()
}

object WbSrcOp extends ChiselEnum {
  val fromAlu, fromPC, fromMem, fromCsr = Value
}

object CsrWbOp extends ChiselEnum {
  val None, Write, Set, Clear = Value
}

class CsrFile extends Module {
  val io = IO(new Bundle {
    val ecall = Input(Bool())
    val idxr  = Input(Tp.CsrIdxType())
    val idxw  = Input(Tp.CsrIdxType())
    val wrMd  = Input(CsrWbOp())
    val data  = Input(Tp.RegType())
    val out   = Output(Tp.RegType())
  })

  val mcycle  = RegInit(0.U(ISA.RegBits.W))
  val mcycleh = RegInit(0.U(ISA.RegBits.W))
  mcycleh := Mux(mcycle.andR, mcycleh + 1.U, mcycleh)
  mcycle  := mcycle + 1.U

  val mvendorid = RegInit(0x79737978L.U)
  val marchid   = RegInit(2510_0264.U)

  val mstatus   = RegInit(0x1800.U(ISA.RegBits.W))
  val mepc      = RegInit(0.U(ISA.RegBits.W))
  val mcause    = RegInit(0.U(ISA.RegBits.W))
  val mtvec     = RegInit(0.U(ISA.RegBits.W))

  // case(Index, Reg, Writable)
  val csrMap = Seq[(UInt, UInt, Boolean)] (
    (0x300.U, mstatus,   true),
    (0x305.U, mtvec,     true),
    (0x341.U, mepc,      true),
    (0x342.U, mcause,   false), // Handled by when block

    (0xB00.U, mcycle,    false),
    (0xB80.U, mcycleh,   false),
    (0xF11.U, mvendorid, false),
    (0xF12.U, marchid,   false)
  )

  // Output
  val csrVal = MuxLookup(io.idxr, 0xBadC0DE.U) (
    csrMap.map { case (idx, reg, _) => idx -> reg }
  )
  io.out := csrVal
  printf(cf"CSR Read ${io.idxr}%x = ${io.out}%x M${io.wrMd}\n")

  // Input
  val wbVal = MuxLookup(io.wrMd, 0.U) (Seq(
    CsrWbOp.Write -> (io.data),
    CsrWbOp.Set   -> (csrVal | io.data),
    CsrWbOp.Clear -> (csrVal & (~io.data))
  ))
  when (io.wrMd =/= CsrWbOp.None) {
    csrMap.foreach{ 
      case (idx, reg, writeable) => {
        if (writeable) {
          when (io.idxw === idx) {
            reg := wbVal
            printf(cf"CSR Write ${io.idxw}%x = ${io.data}%x\n")
          }
        }
      }
    }
  }

  when (io.ecall) {
    mcause := 11.U
  }

  dontTouch(mcycle)
  dontTouch(mcycleh)
  dontTouch(mvendorid)
  dontTouch(marchid)
  dontTouch(mepc)
  dontTouch(mtvec)
  dontTouch(mstatus)
  dontTouch(mcause)
}

class RegFile extends Module {
  val io = IO(new Bundle {
    val rs1  = Input(Tp.RegIdxType())
    val rs2  = Input(Tp.RegIdxType())
    val rd   = Input(Tp.RegIdxType())
    val csrir = Input(Tp.CsrIdxType())
    val csriw = Input(Tp.CsrIdxType())
    val gprdt = Input(Tp.RegType())
    val csrdt = Input(Tp.RegType())
    val gprWE = Input(Bool())
    val csrWM = Input(CsrWbOp())
    val ecall = Input(Bool())
    val rs1V = Output(Tp.RegType())
    val rs2V = Output(Tp.RegType())
    val csrV = Output(Tp.RegType())
  })

  val gprs = Reg(Vec(ISA.RegNum, Tp.RegType()))
  val csrs = Module(new CsrFile())
  csrs.io.data := io.csrdt
  csrs.io.idxr := io.csrir
  csrs.io.idxw := io.csriw
  csrs.io.wrMd := io.csrWM
  csrs.io.ecall := io.ecall

  when (io.gprWE && io.rd.orR) {
    gprs(io.rd) := io.gprdt
  }

  val gpr1V = Mux(io.rs1.orR, gprs(io.rs1), 0.U)
  val gpr2V = Mux(io.rs2.orR, gprs(io.rs2), 0.U)
  val csrV  = csrs.io.out

  io.rs1V := gpr1V 
  io.rs2V := gpr2V
  io.csrV := csrV
  printf(cf"<<REG>> R[${io.rs1}] = ${io.rs1V}%x\n")
  printf(cf"<<REG>> R[${io.rs2}] = ${io.rs2V}%x\n")
  printf(cf"<<REG>> R[${io.rd}] <- ${io.gprdt}%x\n")
}

class IDU extends Module {
  val io = IO(new Bundle {
    val inst   = Input(Tp.InstType())
    val rs1    = Output(Tp.RegIdxType())
    val rs2    = Output(Tp.RegIdxType())
    val rd     = Output(Tp.RegIdxType())
    val csrir  = Output(Tp.CsrIdxType())
    val csriw  = Output(Tp.CsrIdxType())
    val imm    = Output(Tp.RegType())
    val regWr  = Output(Bool())
    val csrWr  = Output(CsrWbOp())
    val memAcc = Output(new MemAccBundle())
    val aluOp  = Output(IntAluOp())
    val aluSel = Output(new AluSelBundle())
    val pcJmp  = Output(new PcJmpBundle())
    val wbSel  = Output(WbSrcOp())
    val ebreak = Output(Bool())
    val ecall  = Output(Bool())
  })

  val opcode = io.inst(6, 0)
  val funct3 = io.inst(14, 12)
  val funct7 = io.inst(31, 25)
  val rvBase  = opcode(1, 0) === 0b11.U(2.W)
  assert(rvBase, cf"Inst[1:0] is not 0b11: opcode=${opcode}%x")

  val (opName, opValid) = InstOp.safe(opcode(6, 2))
  assert(opValid, cf"Invalid opcode encountered: opcode=${opcode}%x")
  val sysOp = SysOp(funct3(1, 0))
  val isEbreak = 
    opName === InstOp.System && sysOp === SysOp.ECall && io.inst(20)
  val isEcall  = 
    opName === InstOp.System && sysOp === SysOp.ECall && ~io.inst(20)
  val isMret   = 
    opName === InstOp.System && sysOp === SysOp.ECall && 
    funct7 === 0b0011000.U && io.inst(21)
  io.ebreak := isEbreak
  io.ecall  := isEcall

  // On ECALL we prepare reg a0 (x10)
  io.rs1    := MuxCase(io.inst(19, 15), Seq(
    isEbreak                -> 10.U,
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
  io.csriw  := Mux(isEcall, 0x341.U, immI(11, 0))
  
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
  val instBr = opName === InstOp.Branch
  val instSys = opName === InstOp.System
  val instCsr = (instSys && sysOp =/= SysOp.ECall)

  /**
    * CSRRC: R[rd] = CSR, CSR &= ~R[rs1] = ~src1 & csr
    * CSRRS: R[rd] = CSR, CSR |=  R[rs1] =  src1 | csr
    * CSRRW: R[rd] = CSR, CSR  =  R[rs1] =     0 + csr
    * We directly pass 0 + src1 to ALU and use ALU result 
    * as csrdt.
    */
  io.csrWr := Mux(instSys, 
    MuxLookup (sysOp, CsrWbOp.None) (Seq(
      SysOp.CsrRC -> CsrWbOp.Clear,
      SysOp.CsrRS -> CsrWbOp.Set,
      SysOp.CsrRW -> CsrWbOp.Write,
      SysOp.ECall -> CsrWbOp.None,
      )), CsrWbOp.None
  )
  io.aluOp := MuxCase (IntAluOp.Add, Seq(
    instArith -> IntAluOp(funct3),
    instBr -> Mux(funct3(1), IntAluOp.Sltu, IntAluOp.Slt)
  ))
  io.aluSel.rs1SelPC  := 
    (opName === InstOp.Auipc) || (opName === InstOp.Jal) || (isEcall)
  io.aluSel.rs2Invert := 
    ((opName === InstOp.OpReg) && funct7(5).asBool) ||
    ((io.aluOp === IntAluOp.Srr) && funct7(5).asBool) ||
    io.aluOp === IntAluOp.Slt ||
    io.aluOp === IntAluOp.Sltu ||
    instBr
  io.aluSel.isBranch := instBr
  io.aluSel.rs2SelImm := ~(
    // instTp === ITYPE.tN || 
    instTp === ITYPE.tR ||
    instTp === ITYPE.tB
  ) || (isEcall)

  // NOTE: imm is always sign-extended
  io.imm    := MuxLookup(instTp, 0.U) (Seq(
    ITYPE.tI -> immI, 
    ITYPE.tU -> immU,
    ITYPE.tS -> immS,
    ITYPE.tJ -> immJ,
    ITYPE.tB -> immB
  ))

  io.memAcc.lenOp := MemLenOp(Mux(
    opName === InstOp.Load || opName === InstOp.Store,
    funct3(1, 0), 0b11.U
  ))
  io.memAcc.isSt := instTp === ITYPE.tS
  io.memAcc.sExt := ~funct3(2)

  io.regWr  := ~(
    instTp === ITYPE.tN || 
    instTp === ITYPE.tB || 
    instTp === ITYPE.tS
  ) || (instCsr)

  io.pcJmp.bIfeq   := funct3 === 0b000.U
  io.pcJmp.bIfne   := funct3 === 0b001.U
  io.pcJmp.bIflt   := (funct3 & 0b101.U) === 0b100.U
  io.pcJmp.bIfge   := (funct3 & 0b101.U) === 0b101.U
  io.pcJmp.bEnable := instBr
  io.pcJmp.jUncond := 
    (opName === InstOp.Jalr) || (opName === InstOp.Jal)
  io.pcJmp.jToCsr  := isEcall || isMret

  io.wbSel := MuxCase(WbSrcOp.fromAlu, Seq(
    instCsr -> WbSrcOp.fromCsr,
    (opName === InstOp.Jalr) -> WbSrcOp.fromPC,
    (opName === InstOp.Jal ) -> WbSrcOp.fromPC,
    (opName === InstOp.Load) -> WbSrcOp.fromMem
  ))

  printf(cf"IDU ${instTp} rs1 ${io.rs1} rs2 ${io.rs2} rd ${io.rd}\n")
  // printf(cf"\trs1 ${io.rs1}%d, rs2 ${io.rs2}%d, imm ${io.imm}%x\n");

}

class EXU extends Module {
  val io = IO(new Bundle {
    val rs1V = Input(Tp.RegType())
    val rs2V = Input(Tp.RegType())
    val pc   = Input(Tp.RegType())
    val imm  = Input(Tp.RegType())
    val sel  = Input(new AluSelBundle())
    val op   = Input(IntAluOp())
    val res  = Output(Tp.RegType())
    val brCmp = Output(new BrCmpBundle())
  })

  val cmp = (io.op === IntAluOp.Sltu || io.op === IntAluOp.Slt)
  val cmpu = io.op === IntAluOp.Sltu
  printf(cf"\trs1 PC?${io.sel.rs1SelPC} : rs2 Imm?${io.sel.rs2SelImm} = ${io.imm}%x\n")
  // printf(cf"\trs1V ${io.rs1V}%x, rs2V ${io.rs2V}%x, imm ${io.imm}%x\n");
  // io.brCmp.beq := false.B
  // io.brCmp.blt := false.B
  val flip = io.sel.rs2Invert && (io.op =/= IntAluOp.Srr)
  val skip = io.sel.isBranch
  val src1 = Mux(io.sel.rs1SelPC, io.pc, io.rs1V)
  val srcc = Mux(io.sel.rs2SelImm, io.imm, io.rs2V)
  val src2 = Mux(flip, ~srcc, srcc)
  printf(cf"\tsrc1${src1}%x : src2${src2}%x\n")
  val ansc = 
    src1.pad(ISA.RegBits+1) + src2.pad(ISA.RegBits+1) + Mux(
      flip, 1.U, 0.U
    )
  // Add, Sltu, Slt
  val anst = MuxCase(ansc(ISA.RegBits-1, 0), Seq(
    (io.op === IntAluOp.Sll) -> (src1 << src2(4, 0)),
    (io.op === IntAluOp.Srr) -> Mux(io.sel.rs2Invert,
      (src1.asSInt >> src2(4, 0)).asUInt, src1 >> src2(4, 0)),
    (io.op === IntAluOp.And) -> (src1 & src2),
    (io.op === IntAluOp.Or ) -> (src1 | src2),
    (io.op === IntAluOp.Xor) -> (src1 ^ src2),
  ))

  val over = (~(src1.MSB() ^ src2.MSB())) & (src1.MSB() ^ anst.MSB())
  val less = Mux(cmpu, ~ansc.MSB(-1), anst.MSB() ^ over)
  io.brCmp.blt := less
  io.brCmp.beq := ~anst.orR
  io.res := MuxCase(anst, Seq(
    skip -> (io.pc + io.imm),
    cmp  -> less.asUInt
  ))
  // printf(cf"\t${src1}%x op ${src2}%x = o${over} c${ansc}%x ${anst}%x\n")
  when (io.sel.isBranch || io.op === IntAluOp.Slt || io.op === IntAluOp.Sltu) {
    printf(cf"Cmp: src1 ${src1}%x, src2 ${src2}%x, "
      + cf"ansc ${ansc}%x OF${over} LT${less} EQ${io.brCmp.beq}\n")
  }
}

/**
  * NOTE: 内置了 iMem 为了防止 memory 接口暴露在顶层.
  */
class LSU extends Module {
  val io = IO(new Bundle {
    val pcin   = Input(Tp.RegType())
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
  iMem.io.data  := MuxLookup(lenOp, 0.U) (Seq(
    MemLenOp.Byte -> (io.data(7, 0) << (io.addr(1,0) << 3.U)),
    MemLenOp.Half -> (io.data(15,0) << (io.addr(1,1) << 4.U)),
    MemLenOp.Word -> io.data
  ))
  iMem.io.byteMask := MuxLookup(lenOp, 0.U) (
    Seq(
      MemLenOp.Byte -> (0x1.U << io.addr(1, 0)),
      MemLenOp.Half -> (0x3.U << (io.addr(1, 1) << 1.U)),
      MemLenOp.Word -> 0xf.U
    )
  )
  iMem.io.memEn := lenOp =/= MemLenOp.None
  // Load and store should not happen together
  iMem.io.wrEn  := io.memAcc.isSt

  val lraw = iMem.io.loadRaw >> (io.addr(1, 0) << 3)
  val sext = io.memAcc.sExt
  // printf(cf"DPI Chisel Raw ${lraw}%x SEXT ${sext}\n")
  io.inst := iMem.io.instRaw
  io.load := MuxLookup(lenOp, 0.U) (Seq(
    MemLenOp.Byte -> Mux(sext, lraw(7, 0).SExt(), lraw(7, 0)),
    MemLenOp.Half -> Mux(sext, lraw(15, 0).SExt(), lraw(15, 0)),
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
    val pc    = Input(Tp.RegType())
    val csrV  = Input(Tp.RegType())
    val aluV  = Input(Tp.RegType())
    val memV  = Input(Tp.RegType())
    val nxpc  = Output(Tp.RegType())
    val csrdt = Output(Tp.RegType())
    val gprdt = Output(Tp.RegType())
  })
  val jar = io.pcJmp.jUncond
  val br  = io.pcJmp.bEnable
  val cd  = io.pcJmp
  val rs  = io.brCmp
  val jmp = jar || (br && (
    (cd.bIfeq && rs.beq) || 
    (cd.bIfne && ~rs.beq) ||
    (cd.bIfge && ~rs.blt) ||
    (cd.bIflt && rs.blt)
  ))
  val snpc = io.pc + 4.U
  when (io.pcJmp.bEnable) {
    printf(cf"Branch if EQ${cd.bIfeq} NE${cd.bIfne} LT${cd.bIflt} GE${cd.bIfge}\n")
    printf(cf"Compare   EQ${rs.beq} NE${~rs.beq} LT${rs.blt} GE${~rs.blt}\n")
  }
  printf(cf"\twbsel ${io.wbSel} alu${io.aluV}%x csr${io.csrV}%x snpc${snpc}%x\n")
  val dnpc = io.aluV(31, 1) ## 0.U(1.W) 
  io.nxpc := MuxCase(snpc, Seq(
    jmp       -> dnpc,
    cd.jToCsr -> io.csrV
  ))
    // Mux(jmp, dnpc, snpc)
  io.gprdt := MuxLookup(io.wbSel, 0.U) (Seq(
    WbSrcOp.fromAlu -> io.aluV, 
    WbSrcOp.fromMem -> io.memV,
    WbSrcOp.fromCsr -> io.csrV,
    WbSrcOp.fromPC  -> snpc
  ))
  io.csrdt := io.aluV
}

class rvCore() extends Module {
  val io = IO(new Bundle{
    // val regPin  = Input(Tp.RegIdxType())
    // val regPrb  = Output(Tp.RegType())
    // val outPC   = Output(Tp.RegType())
  })

  // State
  val pc     = RegInit(0x80000000L.U(ISA.RegBits.W))
  val iReg   = Module(new RegFile())

  // Func
  val iDec   = Module(new IDU())
  val iExe   = Module(new EXU()) 
  val iLsu   = Module(new LSU())
  val iWrite = Module(new WBU())
  val iEcall = Module(new EcallBox())
  // val iDebug = Module(new DebugBox())

  // Probing 
  // io.outPC := pc 
  // iReg.io.rsPin := io.regPin 
  // io.regPrb := iReg.io.regPrb

  // IFU in
  iLsu.io.pcin := pc
  // IFU out
  val inst = iLsu.io.inst
  // printf(cf"[ PC = ${pc}%x ] inst = ${inst}%x\n")

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
  iReg.io.csrir := iDec.io.csrir
  iReg.io.csriw := iDec.io.csriw
  val rs1V = iReg.io.rs1V
  val rs2V = iReg.io.rs2V
  val csrV = iReg.io.csrV
  // Reg write
  iReg.io.rd := iDec.io.rd
  iReg.io.gprWE := iDec.io.regWr
  iReg.io.csrWM := iDec.io.csrWr

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
  // printf(cf"DPI Chisel loadval ${loadV}%x\n")

  // WB in
  iWrite.io.brCmp := br
  iWrite.io.pc   := pc
  iWrite.io.aluV := res
  iWrite.io.memV := loadV
  iWrite.io.csrV  := csrV
  iWrite.io.pcJmp := iDec.io.pcJmp
  iWrite.io.wbSel := iDec.io.wbSel
  // WB out 
  pc            := iWrite.io.nxpc
  iReg.io.gprdt := iWrite.io.gprdt
  iReg.io.csrdt := iWrite.io.csrdt
  iReg.io.ecall := iDec.io.ecall

  // printf(cf"<<<WB>>> rd ${iReg.io.rd} data ${iReg.io.data}%x\n")

  // printf(cf"   R[${iDec.io.rs1}%d]=0x${rs1V}%x R[${iDec.io.rs2}%d]=0x${rs2V}%x "
  //     + cf"Alu=${sAlu.io.sum}%x Eq=${sAlu.io.isEq}\n")

  iEcall.io.clock := this.clock
  iEcall.io.reset := this.reset
  iEcall.io.pcin  := pc
  iEcall.io.a0in  := rs1V
  iEcall.io.isEbreak := iDec.io.ebreak
  iEcall.io.isEcall  := false.B

  // dontTouch(io)
  // dontTouch(iWrite.io)
  // dontTouch(iDec.io)
  // dontTouch(iExe.io)
  // dontTouch(iLsu.io)
}

class rvCoreWrapper() extends Module {
  val io = IO(new Bundle{ })
  val core = Module(new rvCore())
  dontTouch(core.io)
}

