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
    * Expand int mul into Seq[Seq[Bool]], which the sum of 
    * inner Seq[Bool] is the bit of final product.
    *
    * @param op1 Operand 1, sign extended ()
    * @param invop1 ~ Operand 1
    * @param op2 Operand 2, use its b{i+1}b{i}b{i-1} to switch
    */
  def boothRadix4(
    op1:  Seq[Bool],
    nop1: Seq[Bool],
    op2:  Seq[Bool]
  ) = {
    require(op1.size == nop1.size)
    require(op1.size == op2.size)
    val N         = op1.size
    val zero      = 0.U((1 + N).W)
    val op1msb    = op1(N - 1).asBool
    val op2msb    = op2(N - 1).asBool // s, ~n
    val op1pos    = Cat(op1.reverse).asUInt
    val op1inv    = Cat(nop1.reverse).asUInt
    val op1extpos = op1msb ## op1pos
    val op1extinv = (~op1msb) ## op1inv
    val retarrays = Seq.fill(N * 2)(ListBuffer.empty[Bool])

    for (i <- Range(0, N, 2)) {
      val currKey   = Cat(
        if (i + 1 == N) op2msb else op2(i + 1),
        op2(i),
        if (i == 0) 0.B else op2(i - 1)
      ).asUInt
      val currSeq   =
        MuxLookup(
          currKey,
          zero
        )(
          Seq(
            "b000".U -> zero,
            "b001".U -> op1extpos,          // +S ,
            "b010".U -> op1extpos,          // +S ,
            "b011".U -> op1pos ## 0.U(1.W), // +2S,
            "b100".U -> op1inv ## 1.U(1.W), // −2S,
            "b101".U -> op1extinv,          // −S ,
            "b110".U -> op1extinv,          // −S ,
            "b111".U -> zero                // +0 ,
          )
        ).asBools
      val currComps =
        MuxLookup(
          currKey,
          "b00".U
        )(
          Seq(
            "b100".U -> "b10".U, // −2S,
            "b101".U -> "b01".U, // −S ,
            "b110".U -> "b01".U  // −S ,
          )
        ).asBools
      val currMsb   = currSeq(N)
      for (j <- 0 to N) {
        retarrays(i + j) :+ currSeq(j)
      }

      if (i == 0) {
        // nss case
        retarrays(i + N + 3) += (~currMsb)
        retarrays(i + N + 2) += (currMsb)
        retarrays(i + N + 1) += (currMsb)
      } else {
        // 1n case
        if (i + N + 1 < N * 2) retarrays(i + N + 1) += (!currMsb)
        if (i + N + 2 < N * 2) retarrays(i + N + 2) += (currMsb)
      }

      if (i == N - 1 || i == N - 2) {} else {
        // xy
        retarrays(i + 0) += currComps(0)
        retarrays(i + 1) += currComps(1)
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
  val s1Rs1inv = Reg(UInt((ISA.RegBits + 1).W))
  val s1Rs2    = Reg(UInt((ISA.RegBits + 1).W))
  val s1Op     = Reg(MulDivOp())
  val s1Foward = Reg(new DecodeFoward)

  // Pipeline stage 2: result
  val s2Valid  = RegInit(false.B)
  // val s2Sum    = Reg(UInt((2 * (ISA.RegBits + 1)).W))
  // val s2Carry  = Reg(UInt((2 * (ISA.RegBits + 1)).W))
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

  // Stage 0 -> Stage 1
  // 64-bit product: sign-extend to 33 bits based on op
  val isMulh   = s1Op === MulDivOp.Mulh
  val isMulhsu = s1Op === MulDivOp.Mulhsu
  val isMulhu  = s1Op === MulDivOp.Mulhu

  val src1Sgn = !isMulhu && !isMulhsu
  val src2Sgn = !isMulhu

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
      s1Rs1inv := ~src1Full
      s1Rs2    := src2Full
      s1Op     := io.in.bits.op
      s1Foward := io.in.bits.foward
    }
  }

  // val product = (a * b).asUInt // 66-bit result
  val partialProducts    =
    IntMulMath.boothRadix4(
      s1Rs1.asBools,
      s1Rs1inv.asBools,
      s1Rs2.asBools
    )
  val (sumRow, carryRow) = IntMulMath.wallaceReduction(partialProducts)
  val s2Sum   = RegEnable(sumRow, s1Valid)
  val s2Carry = RegEnable(carryRow, s1Valid)
  val product = s2Sum + s2Carry

  val selHigh = s1Op =/= MulDivOp.Mul
  val result  =
    Mux(selHigh, product(63, 32), product(31, 0))

  // Stage 1 -> Stage 2
  when(io.flush) {
    s2Valid := false.B
    s3Valid := false.B
  }.elsewhen(s1Ready) {
    s2Valid := s1Valid
    s3Valid := s2Valid
    when(s2Valid) {
      s3Result := result
      s3Foward := s2Foward
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
