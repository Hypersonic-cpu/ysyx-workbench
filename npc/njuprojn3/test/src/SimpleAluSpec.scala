package NjuProjN3

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import chisel3.util.log2Ceil

object Driver {
  def drv (golden: (Int, Int) => (Int, Int, Int, Int), op: Cmd.Type) = { dut: SimpleAlu =>
    val testInputs = for { 
      x <- 0 until 16 
      y <- 0 until 16
    } yield (x, y)

    val testGolden = testInputs.map { case (x, y) => golden(x,y) }

    var failedTasks:List[((Int,Int),(Int,Int,Int,Int),(BigInt,BigInt,BigInt,BigInt))] = Nil

    // var successCnt: Int = 0
    val totalCnt: Int = testGolden.size
    for (taskPoint <- 0 until totalCnt) {
      val curIn = testInputs(taskPoint)
      val stdOut = testGolden(taskPoint)

      dut.io.in1.poke(curIn._1)
      dut.io.in2.poke(curIn._2)
      dut.io.fn.poke(op.litValue)
      val curOut = dut.io.out.peekValue().asBigInt
      val curCflg = dut.io.cflg.peekValue().asBigInt
      val curOflg = dut.io.oflg.peekValue().asBigInt
      val curZflg = dut.io.zflg.peekValue().asBigInt

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

class SimpleAluSpec extends AnyFreeSpec with Matchers {
  // "SimpleAlu Add should pass" in {
  //   simulate(new SimpleAlu()) { dut =>
  //     val toInt = if (_) 1 else 0
  //     val testGolden = {(x:Int , y:Int) =>
  //       val sx = if (x >= 8) (x - 16) else x
  //       val sy = if (y >= 8) (y - 16) else y
  //
  //       val ures = (x + y) % 16
  //
  //       (
  //         ures, 
  //         toInt ((x + y) >= 16), // carry out
  //         toInt (sx + sy >= 8 || sx + sy < -8), // overflow
  //         toInt (ures == 0)
  //       )
  //     }
  //
  //     val failedList = Driver.drv(testGolden, Cmd.Add) (dut)
  //     for (elem <- failedList) {
  //       println(s"Task Failed: In ${elem._1} Exp ${elem._2} Recv ${elem._3}")
  //     }
  //     assert(failedList.size == 0)
  //   }
  // }

  "SimpleAlu Sub should pass" in {
    simulate(new SimpleAlu()) { dut =>
      val toInt = if (_) 1 else 0
      val testGolden = { (x:Int, y:Int) =>
        val ux = x 
        val uy = (y ^ 0b1111) + 1
        val sx = if (x >= 8) (x - 16) else x
        val sy = if (y >= 8) (y - 16) else y

        val ures = (ux + uy) % 16
        (
          ures, 
          toInt ((ux + uy) >= 16), // carry out
          toInt (sx - sy >= 8 || sx - sy < -8), // overflow
          toInt (ures == 0)
          )
      }
      val failedList = Driver.drv(testGolden, Cmd.Sub) (dut)
      for (elem <- failedList) {
        println(s"Task Failed: In ${elem._1} Exp ${elem._2} Recv ${elem._3}")
      }
      assert(failedList.size == 0)
    }
  }
}
