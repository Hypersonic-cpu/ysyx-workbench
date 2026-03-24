package rvproc

import chisel3._
import chisel3.util._
import scala.annotation.tailrec
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

  private def fullAdder(
    a: Bool,
    b: Bool,
    c: Bool
  ): (Bool, Bool) = {
    val sum   = a ^ b ^ c
    val carry = (a & b) | (a & c) | (b & c)
    (sum, carry)
  }

  private def halfAdder(a: Bool, b: Bool): (Bool, Bool) = {
    val sum   = a ^ b
    val carry = a & b
    (sum, carry)
  }

  private def reduceColumn(
    bits: Seq[Bool]
  ): (Seq[Bool], Seq[Bool]) = {
    bits.grouped(3).foldLeft(
      (Vector.empty[Bool], Vector.empty[Bool])
    ) { case ((sums, carries), chunk) =>
      chunk match {
        case Seq(a, b, c) =>
          val (sum, carry) = fullAdder(a, b, c)
          (sums :+ sum, carries :+ carry)
        case Seq(a, b) =>
          val (sum, carry) = halfAdder(a, b)
          (sums :+ sum, carries :+ carry)
        case Seq(a) =>
          (sums :+ a, carries)
      }
    }
  }

  private def reduceLevel(
    matrix: Seq[Seq[Bool]]
  ): Seq[Seq[Bool]] = {
    val nextLevel =
      Vector.fill(matrix.size + 1)(Vector.empty[Bool])

    matrix.zipWithIndex
      .foldLeft(nextLevel) { case (level, (bits, idx)) =>
        val (sumBits, carryBits) = reduceColumn(bits)
        level
          .updated(idx, level(idx) ++ sumBits)
          .updated(idx + 1, level(idx + 1) ++ carryBits)
      }
      .take(matrix.size)
  }

  @tailrec
  private def reduceToRows(
    matrix: Seq[Seq[Bool]]
  ): Seq[Seq[Bool]] = {
    if (matrix.forall(_.size <= 2)) matrix
    else reduceToRows(reduceLevel(matrix))
  }

  def wallaceReduction(matrix: Seq[Seq[Bool]]): (UInt, UInt) = {
    val reduced = reduceToRows(matrix)
    val row0 = Cat(
      reduced
        .reverse
        .map(bits => bits.headOption.getOrElse(false.B))
    )
    val row1 = Cat(
      reduced
        .reverse
        .map(bits =>
          bits.drop(1).headOption.getOrElse(false.B)
        )
    )
    (row0, row1)
  }

}

/** Pipelined integer multiplier (3-stage pipeline).
  *
  * S1: register inputs
  * S2: Booth Radix-4 + Wallace reduction, register sum/carry
  * S3: final add, select, register result
  *
  * Throughput: 1 op per cycle after 3-cycle latency.
  */
class IntMultiplier extends Module {
  val io = IO(new Bundle {
    val in    = Flipped(Decoupled(new IntMulIn))
    val out   = Decoupled(new IntMulOut)
    val flush = Input(Bool())
  })

  // Pipeline valids
  val s1Valid  = RegInit(false.B)
  val s2Valid  = RegInit(false.B)
  val s3Valid  = RegInit(false.B)

  // Pipeline readiness
  val s2Ready = !s3Valid || io.out.ready
  val s1Ready = !s2Valid || s2Ready
  val s0Ready = !s1Valid || s1Ready
  io.in.ready := s0Ready

  // S1 registers: inputs
  val s1Rs1     = Reg(UInt((ISA.RegBits + 1).W))
  val s1Rs2     = Reg(UInt((ISA.RegBits + 1).W))
  val s1Op      = Reg(MulDivOp())
  val s1Forward = Reg(new DecodeForward)

  // Sign extension from input
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

  // S0 -> S1
  when(io.flush) {
    s1Valid := false.B
  }.elsewhen(s0Ready) {
    s1Valid := io.in.valid
    when(io.in.valid) {
      s1Rs1     := src1Full
      s1Rs2     := src2Full
      s1Op      := io.in.bits.op
      s1Forward := io.in.bits.forward
    }
  }

  // S1: Booth Radix-4 encoding (combinational from s1Rs1, s1Rs2)
  val ppMatrix = IntMulMath.boothRadix4(
    s1Rs1.asBools, s1Rs2.asBools
  )
  val (sumRow, carryRow) =
    IntMulMath.wallaceReduction(ppMatrix)

  // S1 -> S2: register Wallace outputs
  val s2Sum     = RegEnable(sumRow, s1Ready)
  val s2Carry   = RegEnable(carryRow, s1Ready)
  val s2SelHigh = RegEnable(s1Op =/= MulDivOp.Mul, s1Ready)
  val s2Forward = Reg(new DecodeForward)

  // S2: addition + select (combinational from s2 regs)
  val product = s2Sum + s2Carry
  val result  = Mux(s2SelHigh, product(63, 32), product(31, 0))

  // S3 registers: result
  val s3Result  = Reg(Tp.RegType())
  val s3Forward = Reg(new DecodeForward)

  // S1 -> S2 -> S3 valid/forward propagation
  when(io.flush) {
    s2Valid := false.B
    s3Valid := false.B
  }.elsewhen(s1Ready) {
    s2Valid := s1Valid
    when(s1Valid) {
      s2Forward := s1Forward
    }
    when(s2Ready) {
      s3Valid := s2Valid
      when(s2Valid) {
        s3Result  := result
        s3Forward := s2Forward
      }
    }
  }

  // Output
  io.out.valid        := s3Valid
  io.out.bits.result  := s3Result
  io.out.bits.forward := s3Forward
}
