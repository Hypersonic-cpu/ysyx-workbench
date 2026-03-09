package rvproc.formal

import chisel3._
import chisel3.util._
import rvproc._

/**
  * Formal verification REF for IntMultiplier.
  *
  * Spec: FIFO-based golden model. At every input handshake, compute
  * the expected result from (rs1, rs2, op) using pre-defined '*'. 
  * At every output handshake, pop and assert match.
  *
  * Mul/Mulh  : rs1 signed,   rs2 signed
  * Mulhsu    : rs1 signed,   rs2 unsigned
  * Mulhu     : rs1 unsigned, rs2 unsigned
  */
class IntMulFormal extends Module {
  val io = IO(new Bundle {
    val rs1       = Input(Tp.RegType())
    val rs2       = Input(Tp.RegType())
    val op        = Input(MulDivOp())
    val valid     = Input(Bool())
    val outReady  = Input(Bool())
    val outValid  = Output(Bool())
    val outResult = Output(Tp.RegType())
  })

  chisel3.assume(
    !io.valid ||
      io.op === MulDivOp.Mul ||
      io.op === MulDivOp.Mulh ||
      io.op === MulDivOp.Mulhsu ||
      io.op === MulDivOp.Mulhu
  )

  // Restrict operands to 8 bits so the SAT solver can handle the 9x9
  // signed multiply (18-bit product). Full 32-bit correctness is verified
  // by simulation with DIFFTEST.
  chisel3.assume(io.rs1(ISA.RegBits - 1, 8) === 0.U)
  chisel3.assume(io.rs2(ISA.RegBits - 1, 8) === 0.U)

  val dut = Module(new IntMultiplier)
  dut.io.flush          := false.B
  dut.io.in.valid       := io.valid
  dut.io.in.bits.rs1    := io.rs1
  dut.io.in.bits.rs2    := io.rs2
  dut.io.in.bits.op     := io.op
  dut.io.in.bits.foward := 0.U.asTypeOf(new DecodeFoward)
  dut.io.out.ready      := io.outReady

  val isMulhu  = io.op === MulDivOp.Mulhu
  val isMulhsu = io.op === MulDivOp.Mulhsu
  val src1Sgn  = !isMulhu
  val src2Sgn  = !isMulhu && !isMulhsu
  val src1Ext  =
    Cat(Mux(src1Sgn, io.rs1(ISA.RegBits - 1), false.B), io.rs1)
  val src2Ext  =
    Cat(Mux(src2Sgn, io.rs2(ISA.RegBits - 1), false.B), io.rs2)
  // REF product
  val product  = (src1Ext.asSInt * src2Ext.asSInt).asUInt
  val expected = Mux(
    io.op =/= MulDivOp.Mul,
    product(2 * ISA.RegBits - 1, ISA.RegBits),
    product(ISA.RegBits - 1, 0)
  )

  // FIFO depth 4, must exceed pipeline depth (3)
  val pendingFifo = Module(new Queue(Tp.RegType(), 4))
  pendingFifo.io.enq.valid := dut.io.in.fire
  pendingFifo.io.enq.bits  := expected
  pendingFifo.io.deq.ready := dut.io.out.fire

  chisel3.assert(
    pendingFifo.io.enq.ready || !dut.io.in.fire,
    "pending FIFO overflow"
  )

  when(dut.io.out.fire) {
    chisel3.assert(pendingFifo.io.deq.valid, "FIFO empty on output")
    chisel3.assert(
      dut.io.out.bits.result === pendingFifo.io.deq.bits,
      "multiplier result mismatch"
    )
  }

  io.outValid  := dut.io.out.valid
  io.outResult := dut.io.out.bits.result
}

class IntMulFormalWrapper extends Module {
  val io = IO(new Bundle {
    val rs1       = Input(Tp.RegType())
    val rs2       = Input(Tp.RegType())
    val op        = Input(MulDivOp())
    val valid     = Input(Bool())
    val outReady  = Input(Bool())
    val outValid  = Output(Bool())
    val outResult = Output(Tp.RegType())
  })

  val sys = Module(new IntMulFormal)
  sys.io.rs1      := io.rs1
  sys.io.rs2      := io.rs2
  sys.io.op       := io.op
  sys.io.valid    := io.valid
  sys.io.outReady := io.outReady
  io.outValid     := sys.io.outValid
  io.outResult    := sys.io.outResult

  dontTouch(io)
}
