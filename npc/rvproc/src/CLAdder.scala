package rvproc

import chisel3._
import chisel3.util._

// class Comparator extends Module {
//   val io     = IO(new Bundle {
//     val in1 = Input(Tp.RegType())
//     val in2 = Input(Tp.RegType())
//     val out = Output(new BrCmp)
//   })
//   val cmp1s  = io.in1
//   val cmp2s  = ~io.in2
//   val cmpSum = 1.U + cmp1s.UExt() + cmp2s.UExt()
//   val cmpOF  = (~(cmp1s.MSB() ^ cmp2s.MSB())) &
//     (cmp1s.MSB() ^ cmpSum.MSB())
//   val cmpLTU = ~cmpSum.MSB(-1).asBool
//   val cmpLTS = (cmpSum.MSB() ^ cmpOF).asBool
//   val cmpEQ  = ~cmpSum(ISA.RegBits - 1, 0).orR.asBool
//   io.out.bltu := cmpLTU
//   io.out.blts := cmpLTS
//   io.out.beq  := cmpEQ
// }

class CLABlock4 extends Module {
  val io = IO(new Bundle {
    val a    = Input(UInt(4.W))
    val b    = Input(UInt(4.W))
    val cin  = Input(Bool())
    val sum  = Output(UInt(4.W))
    val pGrp = Output(Bool()) // Group Propagate
    val gGrp = Output(Bool()) // Group Generate
  })

  val g = io.a & io.b
  val p = io.a ^ io.b

  val c = Wire(Vec(4, Bool()))
  c(0)    := io.cin
  c(1)    := g(0) | (p(0) & c(0))
  c(2)    := g(1) | (p(1) & g(0)) | (p(1) & p(0) & c(0))
  c(3)    := g(2) | (p(2) & g(1)) | (p(2) & p(1) & g(0)) |
    (p(2) & p(1) & p(0) & c(0))
  io.gGrp := g(3) | (p(3) & g(2)) | (p(3) & p(2) & g(1)) |
    (p(3) & p(2) & p(1) & g(0))

  io.sum  := p ^ Cat(c.map(_.asUInt).reverse)
  io.pGrp := p.andR
}

class CLAdder(N: Int) extends Module {
  require(N == 32 || N == 16)
  val io      = IO(new Bundle {
    val in1 = Input(UInt(N.W))
    val in2 = Input(UInt(N.W))
    val cin = Input(Bool())
    val out = Output(UInt((N + 1).W))
  })
  val NGroups = N / 4
  val clArr   = for { i <- 0 until NGroups } yield Module(new CLABlock4)
  val carry   = Wire(Vec(NGroups + 1, Bool()))

  for (i <- 0 until NGroups) {
    clArr(i).io.a   := io.in1(i * 4 + 3, i * 4)
    clArr(i).io.b   := io.in2(i * 4 + 3, i * 4)
    clArr(i).io.cin := carry(i)
  }
  io.out := carry(NGroups) ## Cat(clArr.map(_.io.sum).reverse)

  for (i <- 0 to NGroups) {
    carry(i) := {
      if (i == 0) { io.cin }
      else {
        clArr(i - 1).io.gGrp || (
          clArr(i - 1).io.pGrp && carry(i - 1)
        )
      }
    }
  }
}
