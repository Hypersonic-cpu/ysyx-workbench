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

/** Pipelined integer multiplier (4-stage pipeline).
  *
  * S1: register inputs, Booth Radix-4 encoding
  * S2: register partial products, Wallace tree reduction
  * S3: register sum/carry, 66-bit addition + select
  * S4: register result, output
  *
  * Throughput: 1 op per cycle after 4-cycle latency.
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
  val s4Valid  = RegInit(false.B)

  // Pipeline readiness
  val s3Ready = !s4Valid || io.out.ready
  val s2Ready = !s3Valid || s3Ready
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

  // S1 -> S2: register partial products column by column
  val s2PPRegs = ppMatrix.map { col =>
    RegEnable(VecInit(col).asUInt, s1Ready)
  }
  val s2SelHigh = RegEnable(s1Op =/= MulDivOp.Mul, s1Ready)
  val s2Forward = Reg(new DecodeForward)

  // S2: Wallace tree reduction (combinational from s2PPRegs)
  val s2PP = s2PPRegs.zipWithIndex.map { case (reg, i) =>
    (0 until ppMatrix(i).size).map(j => reg(j)).toSeq
  }
  val (sumRow, carryRow) =
    IntMulMath.wallaceReduction(s2PP)

  // S2 -> S3: register sum/carry
  val s3Sum     = RegEnable(sumRow, s2Ready)
  val s3Carry   = RegEnable(carryRow, s2Ready)
  val s3SelHigh = RegEnable(s2SelHigh, s2Ready)
  val s3Forward = Reg(new DecodeForward)

  // S3: addition + select (combinational)
  val product = s3Sum + s3Carry
  val result  = Mux(s3SelHigh, product(63, 32), product(31, 0))

  // S4 registers: result
  val s4Result  = Reg(Tp.RegType())
  val s4Forward = Reg(new DecodeForward)

  // S1 -> S2 -> S3 -> S4 valid/forward propagation
  when(io.flush) {
    s2Valid := false.B
    s3Valid := false.B
    s4Valid := false.B
  }.elsewhen(s1Ready) {
    s2Valid := s1Valid
    when(s1Valid) {
      s2Forward := s1Forward
    }
    when(s2Ready) {
      s3Valid := s2Valid
      when(s2Valid) {
        s3Forward := s2Forward
      }
      when(s3Ready) {
        s4Valid := s3Valid
        when(s3Valid) {
          s4Result  := result
          s4Forward := s3Forward
        }
      }
    }
  }

  // Output
  io.out.valid        := s4Valid
  io.out.bits.result  := s4Result
  io.out.bits.forward := s4Forward
}
