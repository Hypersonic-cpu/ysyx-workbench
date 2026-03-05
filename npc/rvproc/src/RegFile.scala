package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert

class CsrFile extends Module {
  val io = IO(new Bundle {
    val idxr      = Input(Tp.CsrIdxType())
    val idxw      = Input(Tp.CsrIdxType())
    val wrEn      = Input(Bool())
    val instRet   = Input(Bool())
    val data      = Input(Tp.RegType())
    val out       = Output(Tp.RegType())
    val excpValid = Input(Bool())
    val excpPC    = Input(Tp.AddrType())
    val excpCause = Input(UInt(4.W))
    val mtvecOut  = Output(Tp.RegType())
  })

  val mcycle    = if (GlbCtrl.debug)
    RegInit(0.U(ISA.RegBits.W))
  else WireDefault(0.U(ISA.RegBits.W))
  val mcycleh   = if (GlbCtrl.debug)
    RegInit(0.U(ISA.RegBits.W))
  else WireDefault(0.U(ISA.RegBits.W))
  if (GlbCtrl.debug) {
    mcycleh := Mux(mcycle.andR, mcycleh + 1.U, mcycleh)
    mcycle  := mcycle + 1.U
  }
  val minstret  = if (GlbCtrl.debug)
    RegInit(0.U(ISA.RegBits.W))
  else WireDefault(0.U(ISA.RegBits.W))
  val minstreth = if (GlbCtrl.debug)
    RegInit(0.U(ISA.RegBits.W))
  else WireDefault(0.U(ISA.RegBits.W))
  if (GlbCtrl.debug) {
    minstreth := Mux(
      minstret.andR,
      minstreth + 1.U,
      minstreth
    )
    minstret := Mux(
      io.instRet,
      minstret + 1.U,
      minstret
    )
  }

  val mvendorid = WireDefault(0x79737978L.U(ISA.RegBits.W))
  val marchid   = WireDefault(2510_0264.U(ISA.RegBits.W))

  val mstatus = RegInit(0x1800.U(ISA.RegBits.W))
  val mepc    = RegInit(0.U(ISA.RegBits.W))
  val mcause  = RegInit(11.U(ISA.RegBits.W))
  val mtvec   = RegInit(0.U(ISA.RegBits.W))

  // case(Index, Reg, Writable)
  val csrMap = Seq[(UInt, UInt, Boolean)](
    (0x300.U, mstatus, true),
    (0x305.U, mtvec, true),
    (0x341.U, mepc, true),
    (0x342.U, mcause, false), // Handled by when block

    (0xb00.U, mcycle, false),
    (0xb02.U, minstret, false),
    (0xb80.U, mcycleh, false),
    (0xb02.U, minstreth, false),
    (0xf11.U, mvendorid, false),
    (0xf12.U, marchid, false)
  )

  // Output
  val csrVal = MuxLookup(io.idxr, 0xbadc0de.U)(
    csrMap.map { case (idx, reg, _) => idx -> reg }
  )
  io.out := csrVal

  io.mtvecOut := mtvec
  // printf(cf"CSR Read ${io.idxr}%x = ${io.out}%x M${io.wrEn}\n")
  //
  // Input
  when(io.wrEn) {
    csrMap.foreach {
      case (idx, reg, writeable) => {
        if (writeable) {
          when(io.idxw === idx) {
            reg := io.data
          }
        }
      }
    }
  }

  // printf(
  //   cf"[ CSR ] C[${io.idxr}%x] = ${io.out}%x"
  //     + cf" C[${io.idxw}%x] <${io.wrEn} ${io.data}%x\n"
  // )

  when(io.excpValid) {
    mepc   := io.excpPC
    mcause := io.excpCause
  }

  if (GlbCtrl.debug) {
    dontTouch(mcycle)
    dontTouch(mcycleh)
    dontTouch(mvendorid)
    dontTouch(marchid)
    dontTouch(mepc)
    dontTouch(mtvec)
    dontTouch(mstatus)
    dontTouch(mcause)
  }
}

class GprFile extends Module {
  val io = IO(new Bundle {
    val rs1  = Input(Tp.RegIdxType())
    val rs2  = Input(Tp.RegIdxType())
    val rd   = Input(Tp.RegIdxType())
    val data = Input(Tp.RegType())
    val wrEn = Input(Bool())
    val rs1V = Output(Tp.RegType())
    val rs2V = Output(Tp.RegType())
  })

  val gprs = Reg(Vec(ISA.RegNum, Tp.RegType()))

  when(io.wrEn && io.rd.orR) {
    gprs(io.rd) := io.data
  }

  val gpr1V = Mux(io.rs1.orR, gprs(io.rs1), 0.U)
  val gpr2V = Mux(io.rs2.orR, gprs(io.rs2), 0.U)

  io.rs1V := gpr1V
  io.rs2V := gpr2V
  // printf(
  //   cf"[ GPR ] R[${io.rs1}] = ${io.rs1V}%x"
  //     + cf" R[${io.rs2}] = ${io.rs2V}%x"
  //     + cf" R[${io.rd}] <${io.wrEn} ${io.data}%x\n"
  // )
}

class RegFile extends Module {
  val io = IO(new Bundle {
    val fromWb   = Flipped(Decoupled(new RegFromWBU))
    val fromId   = Flipped(Decoupled(new RegFromIDU))
    val toId     = Decoupled(new RegToIDU)
    val mtvecOut = Output(Tp.RegType())
  })

  io.toId.valid   := true.B
  io.fromId.ready := false.B
  io.fromWb.ready := false.B

  val gpr = Module(new GprFile)
  val csr = Module(new CsrFile)

  val ioid    = io.fromId.bits
  val iowb    = io.fromWb.bits
  val out     = io.toId.bits
  val wbValid = io.fromWb.valid

  gpr.io.rs1  := ioid.rs1
  gpr.io.rs2  := ioid.rs2
  gpr.io.rd   := iowb.gprRd
  gpr.io.data := iowb.gprIn
  gpr.io.wrEn := iowb.gprWE && wbValid
  out.rs1Val  := gpr.io.rs1V
  out.rs2Val  := gpr.io.rs2V

  csr.io.idxr      := ioid.csrr
  csr.io.idxw      := iowb.csrRd
  csr.io.wrEn      := iowb.csrWE && wbValid
  csr.io.data      := iowb.csrIn
  csr.io.excpValid := iowb.excpValid && wbValid
  csr.io.excpPC    := iowb.excpPC
  csr.io.excpCause := iowb.excpCause
  csr.io.instRet   := wbValid
  out.csrVal       := csr.io.out
  out.mtvecVal     := csr.io.mtvecOut
  io.mtvecOut      := csr.io.mtvecOut
}
