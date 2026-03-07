package rvproc

import chisel3._
import chisel3.util._

/** Iterative integer divider (non-restoring algorithm).
  *
  * Takes ~34 cycles per division. Produces both quotient
  * and remainder. Handles signed (DIV/REM) and unsigned
  * (DIVU/REMU) operations, plus division-by-zero and
  * overflow (MIN_INT / -1) corner cases per RISC-V spec.
  *
  * Interface: DecoupledIO handshake.
  */
class IntDivider extends Module {
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

  val idle :: running :: done :: Nil = Enum(3)
  val state                          = RegInit(idle)

  val cnt       = RegInit(0.U(6.W))
  val negQ      = Reg(Bool())     // negate quotient
  val negR      = Reg(Bool())     // negate remainder
  val isRem     = Reg(Bool())
  val rd        = Reg(Tp.RegIdxType())
  val foward    = Reg(new DecodeFoward)
  val quotient  = Reg(UInt(32.W))
  val remainder = Reg(UInt(33.W)) // 33-bit accumulator
  val divisor   = Reg(UInt(33.W))
  val result    = Reg(Tp.RegType())
  val special   = Reg(Bool())     // corner case handled

  io.in.ready  := state === idle
  io.out.valid := state === done

  // Corner cases per RISC-V spec
  val isSigned  =
    io.in.bits.op === MulDivOp.Div ||
      io.in.bits.op === MulDivOp.Rem
  val divByZero = io.in.bits.rs2 === 0.U
  val overflow  = isSigned &&
    io.in.bits.rs1 === "h80000000".U(32.W) &&
    io.in.bits.rs2 === "hffffffff".U(32.W)
  val wantRem   =
    io.in.bits.op === MulDivOp.Rem ||
      io.in.bits.op === MulDivOp.Remu

  // Absolute values for signed division
  val rs1Neg = isSigned && io.in.bits.rs1(31)
  val rs2Neg = isSigned && io.in.bits.rs2(31)
  val absRs1 = Mux(rs1Neg, -io.in.bits.rs1, io.in.bits.rs1)
  val absRs2 = Mux(rs2Neg, -io.in.bits.rs2, io.in.bits.rs2)

  switch(state) {
    is(idle) {
      when(io.in.valid && !io.flush) {
        rd     := io.in.bits.rd
        foward := io.in.bits.foward
        isRem  := wantRem
        negQ   := rs1Neg ^ rs2Neg
        negR   := rs1Neg

        when(divByZero) {
          // Div by zero: quot = -1, rem = dividend
          result  := Mux(
            wantRem,
            io.in.bits.rs1,
            "hffffffff".U(32.W)
          )
          special := true.B
          state   := done
        }.elsewhen(overflow) {
          // Signed overflow: quot = MIN_INT, rem = 0
          result  := Mux(
            wantRem,
            0.U,
            "h80000000".U(32.W)
          )
          special := true.B
          state   := done
        }.otherwise {
          quotient  := absRs1
          remainder := 0.U
          divisor   := Cat(0.U(1.W), absRs2)
          special   := false.B
          cnt       := 0.U
          state     := running
        }
      }
    }

    is(running) {
      when(io.flush) {
        state := idle
      }.otherwise {
        // Non-restoring division step
        val shifted = Cat(
          remainder(31, 0),
          quotient(31)
        )
        val trial   = shifted -& divisor

        when(!trial(32)) {
          // Trial >= 0: subtract succeeded
          remainder := trial
          quotient  := Cat(quotient(30, 0), 1.U(1.W))
        }.otherwise {
          // Trial < 0: keep old value
          remainder := shifted
          quotient  := Cat(quotient(30, 0), 0.U(1.W))
        }

        cnt := cnt + 1.U
        when(cnt === 31.U) {
          state := done
        }
      }
    }

    is(done) {
      when(io.flush) {
        state := idle
      }.elsewhen(io.out.ready) {
        state := idle
      }
    }
  }

  // Sign correction and result selection
  val rawQ  = quotient
  val rawR  = remainder(31, 0)
  val corrQ = Mux(negQ && !special, -rawQ, rawQ)
  val corrR = Mux(negR && !special, -rawR, rawR)

  io.out.bits.result := Mux(
    special,
    result,
    Mux(isRem, corrR, corrQ)
  )
  io.out.bits.rd     := rd
  io.out.bits.foward := foward
}
