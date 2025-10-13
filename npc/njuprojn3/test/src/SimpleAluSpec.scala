package NjuProjN3

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import chisel3.util.log2Ceil

/** This is a trivial example of how to run this Specification From within sbt use:
  * {{{
  * testOnly gcd.GCDSpec
  * }}}
  * From a terminal shell use:
  * {{{
  * sbt 'testOnly gcd.GCDSpec'
  * }}}
  * Testing from mill:
  * {{{
  * mill %NAME%.test.testOnly gcd.GCDSpec
  * }}}
  */
class Encoder8to3Spec extends AnyFreeSpec with Matchers {
  // "Gcd should calculate proper greatest common denominator" in {
  "Encoder8to3 should pass" in {
    simulate(new Encoder8to3()) { dut =>
      val testInputs = for { x <- 0 until 256 } yield (x)
      val testGolden = testInputs.map ( x => math.max(log2Ceil(x+1).toInt - 1, 0) )
      val testValidB = testInputs.map ( x => (x != 0) )

      // println(testInputs)
      // println(testGolden)

      // val inputSeq   = testValues.map { case (x) => (new Encoder8to3()).Lit(_.value1 -> x.U) }
      // val resultSeq  = testValues.map { case (x) =>
      //   (new GcdOutputBundle(16)).Lit(_.value1 -> x.U, _.value2 -> y.U, _.gcd -> BigInt(x).gcd(BigInt(y)).U)
      // }

      var successCnt: Int = 0
      val totalCnt: Int = testGolden.size
      for (taskPoint <- 0 until totalCnt) {
        val curIn = testInputs(taskPoint)
        val stdOut = testGolden(taskPoint)
        val validB = testValidB(taskPoint)

        dut.io.in.poke(curIn)
        val curOut = dut.io.out.peekValue().asBigInt
        val validOut = dut.io.valid.peekValue().asBigInt == 1

        print(s"#$taskPoint: in = $curIn, expect $stdOut (V $validB), recv $curOut (V $validOut) ")
        if (curOut === stdOut && validB === validOut) {
          println("Passed")
          successCnt += 1
        } else {
          println("Failed")
        }
      }
      println(s"Total $totalCnt, passed $successCnt, failed ${totalCnt-successCnt}")
      assert(totalCnt == successCnt)

      // var sent, received, cycles: Int = 0
      // while (sent != 100 && received != 100) {
      //   assert(cycles <= 1000, "timeout reached")
      //
      //   if (sent < 100) {
      //     dut.input.valid.poke(true.B)
      //     dut.input.bits.value1.poke(testValues(sent)._1.U)
      //     dut.input.bits.value2.poke(testValues(sent)._2.U)
      //     if (dut.input.ready.peek().litToBoolean) {
      //       sent += 1
      //     }
      //   }
      //
      //   if (received < 100) {
      //     dut.output.ready.poke(true.B)
      //     if (dut.output.valid.peekValue().asBigInt == 1) {
      //       dut.output.bits.gcd.expect(BigInt(testValues(received)._1).gcd(testValues(received)._2))
      //       received += 1
      //     }
      //   }
      //
      //   // Step the simulation forward.
      //   dut.clock.step()
      //   cycles += 1
      // }
    }
  }
}
