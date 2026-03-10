package rvproc

import chisel3._
import chisel3.util._
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.ListBuffer

object IntMulMath {
  // n = ~s,
  //  MSB                         3210
  //              nss*****************
  //             1n*****************xy
  //           1n*****************xy
  //         1n*****************xy
  //       1n*****************xy
  //     1n*****************xy
  //   1n*****************xy
  //  n*****************xy

  /**
    * Booth radix-4 partial product generation using direct
    * boolean logic (no MuxLookup). Each bit is computed as:
    * pp[j] = active & (neg ^ ((one & M[j]) | (two & M[j-1])))
    *
    * @param op1 Operand 1, sign extended (N+1 bits)
    * @param op2 Operand 2, sign extended (N+1 bits)
    */
  def boothRadix4(
    op1: Seq[Bool],
    op2: Seq[Bool]
  ) = {
    require(op1.size == op2.size)
    val N         = op1.size
    val op1msb    = op1(N - 1).asBool
    val op2msb    = op2(N - 1).asBool
    val retarrays = Seq.fill(N * 2)(ListBuffer.empty[Bool])

    for (i <- Range(0, N, 2)) {
      val b2 =
        if (i + 1 == N) op2msb else op2(i + 1)
      val b1 = op2(i)
      val b0 = if (i == 0) 0.B else op2(i - 1)

      val neg    = b2
      val one    = b1 ^ b0
      val two    = (b2 ^ b1) & (~one)
      val active = one | two

      for (j <- 0 to N) {
        val mj = if (j < N) op1(j) else op1msb
        val mj1 =
          if (j == 0) 0.B
          else if (j - 1 < N) op1(j - 1)
          else op1msb
        val ppBit =
          active & (neg ^ ((one & mj) | (two & mj1)))
        if (i + j < N * 2) retarrays(i + j) += ppBit
      }

      val currMsb = active & (neg ^ op1msb)

      if (i == 0) {
        retarrays(i + N + 3) += (~currMsb)
        retarrays(i + N + 2) += (currMsb)
        retarrays(i + N + 1) += (currMsb)
      } else {
        if (i + N + 1 < N * 2)
          retarrays(i + N + 1) += (!currMsb)
        if (i + N + 2 < N * 2)
          retarrays(i + N + 2) += true.B
      }

      if (i == N - 1 || i == N - 2) {} else {
        retarrays(i) += (neg & active)
      }
    }
    retarrays.map(_.toSeq)
  }

  def wallaceReduction(matrix: Seq[Seq[Bool]]): (UInt, UInt) = {
    var currentLevel = matrix.map(_.toList)
    val maxColumn    = currentLevel.size

    def reduceColumn(bits: List[Bool]): (List[Bool], List[Bool]) = {
      var remaining = bits
      val reduced   = ListBuffer.empty[Bool]
      val carries   = ListBuffer.empty[Bool]

      while (remaining.size >= 3) {
        val a     = remaining(0)
        val b     = remaining(1)
        val c     = remaining(2)
        val sum   = a ^ b ^ c
        val carry = (a & b) | (a & c) | (b & c)
        reduced += sum
        carries += carry
        remaining = remaining.drop(3)
      }
      if (remaining.size == 2) {
        val a = remaining(0)
        val b = remaining(1)
        reduced += (a ^ b)
        carries += (a & b)
        remaining = remaining.drop(2)
      }
      if (remaining.size == 1) {
        reduced += remaining(0)
        remaining = remaining.drop(1)
      }
      (reduced.toList, carries.toList)
    }

    while (currentLevel.exists(_.size > 2)) {
      val nextLevel = Array.fill(maxColumn + 1)(
        ListBuffer.empty[Bool]
      )

      for (i <- 0 until maxColumn) {
        val (sumBits, carryBits) = reduceColumn(currentLevel(i))
        nextLevel(i) ++= sumBits
        if (i + 1 < nextLevel.size) {
          nextLevel(i + 1) ++= carryBits
        }
      }
      currentLevel = nextLevel.take(maxColumn).map(_.toList).toSeq
    }

    val row0 = Cat(
      currentLevel
        .map(bits => if (bits.nonEmpty) bits(0) else 0.B)
        .reverse
    )
    val row1 = Cat(
      currentLevel
        .map(bits => if (bits.size > 1) bits(1) else 0.B)
        .reverse
    )
    (row0, row1)
  }

}

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
    val in    = Flipped(Decoupled(new IntMulIn))
    val out   = Decoupled(new IntMulOut)
    val flush = Input(Bool())
  })

  // Pipeline stage 1: registered inputs
  val s1Valid  = RegInit(false.B)
  val s1Rs1    = Reg(UInt((ISA.RegBits + 1).W))
  val s1Rs2    = Reg(UInt((ISA.RegBits + 1).W))
  val s1Op     = Reg(MulDivOp())
  val s1Foward = Reg(new DecodeFoward)

  // Pipeline stage 2: Booth+Wallace sum/carry registered here
  val s2Valid  = RegInit(false.B)
  val s2Foward = Reg(new DecodeFoward)

  // Pipeline stage 3: result
  val s3Valid  = RegInit(false.B)
  val s3Result = Reg(Tp.RegType())
  val s3Foward = Reg(new DecodeFoward)

  // Stage 1 accepts when stage 2 can accept or is empty
  val s2Ready = !s3Valid || io.out.ready
  val s1Ready = !s2Valid || s2Ready
  val s0Ready = !s1Valid || s1Ready
  io.in.ready := s0Ready

  // Stage 0 -> Stage 1: sign-extend from CURRENT input op.
  // Mul/Mulh : rs1 signed, rs2 signed
  // Mulhsu   : rs1 signed, rs2 unsigned
  // Mulhu    : rs1 unsigned, rs2 unsigned
  val inIsMulhsu = io.in.bits.op === MulDivOp.Mulhsu
  val inIsMulhu  = io.in.bits.op === MulDivOp.Mulhu
  val src1Sgn    = !inIsMulhu
  val src2Sgn    = !inIsMulhu && !inIsMulhsu

  val src1Full = Cat(
    Mux(src1Sgn, io.in.bits.rs1(31), 0.U(1.W)),
    io.in.bits.rs1
  )
  val src2Full = Cat(
    Mux(src2Sgn, io.in.bits.rs2(31), 0.U(1.W)),
    io.in.bits.rs2
  )

  when(io.flush) {
    // NOTE: `flush` represents "this calc is useless".
    // Cache `fence` represents "next calc is not ready".
    s1Valid := false.B
  }.elsewhen(s0Ready) {
    s1Valid := io.in.valid
    when(io.in.valid) {
      s1Rs1    := src1Full
      s1Rs2    := src2Full
      s1Op     := io.in.bits.op
      s1Foward := io.in.bits.foward
    }
  }

  // Stage 1 -> Stage 2: Booth Radix-4 partial products + Wallace
  // tree reduction. One cycle for Booth+Wallace (combinational),
  // next cycle for the final 66-bit addition.
  val partialProducts    =
    IntMulMath.boothRadix4(
      s1Rs1.asBools,
      s1Rs2.asBools
    )
  val (sumRow, carryRow) =
    IntMulMath.wallaceReduction(partialProducts)
  val s2Sum     = RegEnable(sumRow, s1Ready)
  val s2Carry   = RegEnable(carryRow, s1Ready)
  val s2SelHigh = RegEnable(s1Op =/= MulDivOp.Mul, s1Ready)
  val product   = s2Sum + s2Carry

  val result = Mux(s2SelHigh, product(63, 32), product(31, 0))

  // Stage 1 -> Stage 2
  when(io.flush) {
    s2Valid := false.B
    s3Valid := false.B
  }.elsewhen(s1Ready) {
    s2Valid := s1Valid
    // S2->S3 only when S3 can accept; otherwise S3 holds its current value.
    when(s2Ready) {
      s3Valid := s2Valid
      when(s2Valid) {
        s3Result := result
        s3Foward := s2Foward
      }
    }
    when(s1Valid) {
      s2Foward := s1Foward
    }
  }

  // Output
  io.out.valid       := s3Valid
  io.out.bits.result := s3Result
  io.out.bits.foward := s3Foward
}
