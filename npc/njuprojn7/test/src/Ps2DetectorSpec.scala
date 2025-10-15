package NjuProjN7

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import chisel3.util.log2Ceil

object Driver {
  def drv () = { dut: Ps2Decoder =>
    val testInputs = for { 
      x <- 0 until 16 
      y <- 0 until 16
    } yield (x, y)

    // val testGolden = testInputs.map { case (x, y) => golden(x,y) }
    //
    // var failedTasks:List[((Int,Int),(Int,Int,Int,Int),(BigInt,BigInt,BigInt,BigInt))] = Nil
    //
    // // var successCnt: Int = 0
    // val totalCnt: Int = testGolden.size
    // for (taskPoint <- 0 until totalCnt) {
    //   val curIn = testInputs(taskPoint)
    //   val stdOut = testGolden(taskPoint)
    //
    //   dut.io.in1.poke(curIn._1)
    //   dut.io.in2.poke(curIn._2)
    //   dut.io.fn.poke(op.litValue)
    //   val curOut = dut.io.out.peekValue().asBigInt
    //   val curCflg = dut.io.cflg.peekValue().asBigInt
    //   val curOflg = dut.io.oflg.peekValue().asBigInt
    //   val curZflg = dut.io.zflg.peekValue().asBigInt
    //
    //   // TODO: dprintf control
    //
    //   dut.io.clk.step()
    //   val curCmp = (curOut, curCflg, curOflg, curZflg)
    //   // print(s"#$taskPoint: in = $curIn, expect $stdOut (V $validB), recv $curOut (V $validOut) ")
    //   if (curCmp == stdOut) {
    //     // print(s"#$taskPoint: op $op , in = $curIn, expect $stdOut, recv $curCmp ")
    //     // println("Passed")
    //   } else {
    //     // print(s"#$taskPoint: op $op , in = $curIn, expect $stdOut, recv $curCmp ")
    //     // println("Failed")
    //     failedTasks = (curIn, stdOut, curCmp) :: failedTasks
    //   }
    // }
    // failedTasks
  }
}

class Ps2DecoderSpec extends AnyFreeSpec with Matchers {
  "SimpleAlu Add should pass" in {
    simulate(new Ps2Detector()) { dut =>
    }
  }

  
}
