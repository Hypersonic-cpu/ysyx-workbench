package NjuProjN7

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import chisel3.util.log2Ceil
import chiseltest.ChiselScalatestTester
import chiseltest.simulator.WriteVcdAnnotation

object Driver {
}

class Ps2DetectorSpec extends AnyFreeSpec with Matchers with ChiselScalatestTester {
  "PS2 decoder should pass" in {
    test(new Ps2Detector()).withAnnotations(Seq(
      WriteVcdAnnotation
    )){ dut =>
      val ps2Val = List(0x12, 0x1f, 0xAA)
      val n = 6
      // Postive edge clock
      dut.clock.step(n)
      println(f"\t1")
      dut.reset.poke(1)
      println(f"\t1")
      dut.clock.step(n)
      println(f"\t1")
      dut.reset.poke(0)
      println(f"\t1")
      dut.io.acqOut.poke(0)
      println(f"\t1")
      dut.clock.step(n)
      println(f"\t1")
      // for (ps2v <- ps2Val) {
      //   println(f"\tProcessing input ${ps2v}%x")
      //   dut.io.ps2Dat.poke(0)
      //   dut.clock.step(n)
      //   dut.io.ps2Clk.poke(1)
      //   dut.clock.step(n)
      //   dut.io.ps2Clk.poke(0)
      //
      //
      //   var parity = 0x1
      //   for (i <- 0 until 8) {
      //     val bit = (ps2v >> i) & 0x1
      //     parity = parity ^ bit
      //     dut.io.ps2Dat.poke(bit)
      //     dut.clock.step(n)
      //     dut.io.ps2Clk.poke(1)
      //     dut.clock.step(n)
      //     dut.io.ps2Clk.poke(0)
      //   }
      //   dut.io.ps2Dat.poke(parity & 0x1)
      //   dut.clock.step(n)
      //   dut.io.ps2Clk.poke(1)
      //   dut.clock.step(n)
      //   dut.io.ps2Clk.poke(0)
      //   // assert(dut.io.outEn.peekValue().asBigInt == 1)
      // }
      //
      // dut.clock.step(n)
      // var retList: List[BigInt] = Nil
      // for (ps2v <- ps2Val) {
      //   // assert(dut.io.outEn.peekValue().asBigInt == 1)
      //   dut.io.acqOut.poke(1)
      //   dut.clock.step(n)
      //   val outData = dut.io.outDt.peekValue().asBigInt
      //   println(f"\tExpect ${ps2v}%x, get ${outData}%x")
      //   retList = outData :: retList
      //   // assert(outData == ps2v)
      // }
      // val retLis = retList.reverse
      // println(retList)
    } 
  }
}
