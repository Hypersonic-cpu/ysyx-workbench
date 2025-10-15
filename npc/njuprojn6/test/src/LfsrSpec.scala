package NjuProjN6

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import chisel3.util.log2Ceil

object Driver {
  def drv (maxCycle:Int, loadCyc:Int, loadVal:Int) = { dut: Lfsr =>
    val testGolden = { 
      // seq.rev.asArray[i-1] = output after cycle i
      var seq:List[Int] = Nil
      var state = 0
      var next_state = 1
      // Cycle 0: reset hi    Nil
      // Cycle 1: state = 1   1 :: Nil
      // Cycle 2: ...
      for (cyc <- 1 until maxCycle) {
        state = next_state
        seq = state :: seq
        val feedback = 
          ((state &0b10000) >> 4) ^
          ((state & 0b1000) >> 3) ^
          ((state &  0b100) >> 2) ^
          ((state &    0b1) >> 0)

        next_state = 
          if (loadCyc == cyc) loadVal 
          else (state >> 1) | (feedback << 7)
        if (next_state == 0) { next_state = 1 }
      }

      seq.reverse.toArray
    }

    val testActual = {
      dut.reset.poke(true.B)
      dut.clock.step(1)
      var seq = dut.io.out.peekValue().asBigInt :: Nil
      dut.reset.poke(false.B)

      for (cyc <- 1 until maxCycle) {
        if (cyc == loadCyc) {
          dut.io.load.poke(1.B)
          dut.io.ldVal.poke(loadVal)
          dut.clock.step()
          dut.io.load.poke(0.B)
        } else {
          dut.clock.step()
        }
        seq = dut.io.out.peekValue().asBigInt :: seq
      }

      seq.reverse.toArray
    }

    val testResult = testGolden.zip(testActual).zipWithIndex
    val firstDiff = testResult.find{case ((idealx, realx), idx) => idealx != realx}
    (firstDiff) match {
      case None => Array.empty[((Int, BigInt), Int)]
      case Some(v) => testResult.drop(v._2)
    }
    // testResult
  }
}

class LfsrSpec extends AnyFreeSpec with Matchers {
  "LFSR should generate the same sequence" in {
    simulate(new Lfsr()) {
      dut: Lfsr => {
        val errorSeq = Driver.drv(300, -1, -1) (dut)
        for (case ((ideal, real), index) <- errorSeq) {
          println(f"#${index}%3d  Exp 0x${ideal}%03x  Recv 0x${real.intValue}%03x")
        }
        assert(errorSeq.length == 0)
      }
    }
  }

  "LFSR should load value correctly" in {
    simulate(new Lfsr()) {
      dut: Lfsr => {
        val errorSeq = Driver.drv(300, 22, 0b1111_1111) (dut)
        for (case ((ideal, real), index) <- errorSeq) {
          println(f"#${index}%3d  Exp 0x${ideal}%03x  Recv 0x${real.intValue}%03x")
        }
        assert(errorSeq.length == 0)
      }
    }
  }

  "LFSR should leave the off-cycle (zero) state" in {
    simulate(new Lfsr()) {
      dut: Lfsr => {
        val errorSeq = Driver.drv(300, 22, 0b0000_0000) (dut)
        for (case ((ideal, real), index) <- errorSeq) {
          println(f"#${index}%3d  Exp 0x${ideal}%03x  Recv 0x${real.intValue}%03x")
        }
        assert(errorSeq.length == 0)
      }
    }
  }
}
