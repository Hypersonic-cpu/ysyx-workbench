package rvproc

import chisel3._
import chisel3.util._

/** Pipelined integer multiplier (2-stage pipeline).
  *
  * Stage 1: register inputs, compute partial products
  * Stage 2: produce 64-bit result, select output word
  *
  * Interface: DecoupledIO handshake. Throughput: 1 op
  * per cycle after 2-cycle latency.
  */
class IntMultiplier extends Module {
  val io = IO(new Bundle {
    val in    = Flipped(Decoupled(new Bundle {
      val rs1    = Tp.RegType()
      val rs2    = Tp.RegType()
      val op     = MulDivOp()
      val rd     = Tp.RegIdxType()
      val foward = new DecodeFoward
    }))
    val out   = Decoupled(new Bundle {
      val result = Tp.RegType()
      val rd     = Tp.RegIdxType()
      val foward = new DecodeFoward
    })
    val flush = Input(Bool())
  })

  // Pipeline stage 1: registered inputs
  val s1Valid  = RegInit(false.B)
  val s1Rs1    = Reg(Tp.RegType())
  val s1Rs2    = Reg(Tp.RegType())
  val s1Op     = Reg(MulDivOp())
  val s1Rd     = Reg(Tp.RegIdxType())
  val s1Foward = Reg(new DecodeFoward)

  // Pipeline stage 2: result
  val s2Valid  = RegInit(false.B)
  val s2Result = Reg(Tp.RegType())
  val s2Rd     = Reg(Tp.RegIdxType())
  val s2Foward = Reg(new DecodeFoward)

  // Stage 1 accepts when stage 2 can accept or is empty
  val s1Ready = !s2Valid || io.out.ready
  val s0Ready = !s1Valid || s1Ready
  io.in.ready := s0Ready

  // Stage 0 → Stage 1
  when(io.flush) {
    s1Valid := false.B
  }.elsewhen(s0Ready) {
    s1Valid := io.in.valid
    when(io.in.valid) {
      s1Rs1    := io.in.bits.rs1
      s1Rs2    := io.in.bits.rs2
      s1Op     := io.in.bits.op
      s1Rd     := io.in.bits.rd
      s1Foward := io.in.bits.foward
    }
  }

  // Compute 64-bit product in stage 1→2 transition
  // Sign handling: extend to 33 bits based on op
  val isMulh   = s1Op === MulDivOp.Mulh
  val isMulhsu = s1Op === MulDivOp.Mulhsu
  val isMulhu  = s1Op === MulDivOp.Mulhu

  val src1Sgn = !isMulhu && !isMulhsu
  val src2Sgn = !isMulhu

  val a = Cat(
    Mux(src1Sgn, s1Rs1(31), 0.U(1.W)),
    s1Rs1
  ).asSInt
  val b = Cat(
    Mux(src2Sgn, s1Rs2(31), 0.U(1.W)),
    s1Rs2
  ).asSInt

  val product = (a * b).asUInt // 66-bit result

  val selHigh = s1Op =/= MulDivOp.Mul
  val result  =
    Mux(selHigh, product(63, 32), product(31, 0))

  // Stage 1 → Stage 2
  when(io.flush) {
    s2Valid := false.B
  }.elsewhen(s1Ready) {
    s2Valid := s1Valid
    when(s1Valid) {
      s2Result := result
      s2Rd     := s1Rd
      s2Foward := s1Foward
    }
  }

  // Output
  io.out.valid       := s2Valid
  io.out.bits.result := s2Result
  io.out.bits.rd     := s2Rd
  io.out.bits.foward := s2Foward
}
