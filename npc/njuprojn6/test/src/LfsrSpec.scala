package NjuProjN6

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import chisel3.util.log2Ceil

object Driver {
  def drv () = { dut: Lfsr =>
    val testGolden = { 
      // seq.rev.asArray[i-1] = output after cycle i
      var seq:List[Int] = Nil
      var state = 0
      var next_state = 1
      // Cycle 0: reset hi    Nil
      // Cycle 1: state = 1   1 :: Nil
      // Cycle 2: ...
      for (cyc <- 1 until 30) {
        state = next_state
        seq = state :: seq
        val feedback = 
          ((state & 0b10000) >> 4)
          ^ ((state & 0b1000) >> 3)
          ^ ((state &  0b100) >> 2)
          ^ ((state &    0b1) >> 0)

        next_state = (state >> 1) | (feedback << 7)
        if (next_state == 0) { next_state = 1 }
      }
      seq.reverse.toArray()
    }

    dut.reset.poke(true.B)

    for (taskPoint <- 1 until totalCnt) {
      dut.io.in1.poke(curIn._1)
      dut.io.in2.poke(curIn._2)
      dut.io.fn.poke(op.litValue)
      val curOut = dut.io.out.peekValue().asBigInt
      val curCflg = dut.io.cflg.peekValue().asBigInt
      val curOflg = dut.io.oflg.peekValue().asBigInt
      val curZflg = dut.io.zflg.peekValue().asBigInt

      // TODO: dprintf control
      dut.io.clk.step()
      val curCmp = (curOut, curCflg, curOflg, curZflg)
      // print(s"#$taskPoint: in = $curIn, expect $stdOut (V $validB), recv $curOut (V $validOut) ")
      if (curCmp == stdOut) {
        // print(s"#$taskPoint: op $op , in = $curIn, expect $stdOut, recv $curCmp ")
        // println("Passed")
      } else {
        // print(s"#$taskPoint: op $op , in = $curIn, expect $stdOut, recv $curCmp ")
        // println("Failed")
        failedTasks = (curIn, stdOut, curCmp) :: failedTasks
      }
    }
    failedTasks
  }
}

class LfsrSpec extends AnyFreeSpec with Matchers {
  
}
