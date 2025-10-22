package NjuProjN7

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

// import chisel3.experimental.BundleLiterals._
// import chisel3.simulator.EphemeralSimulator._
// import org.scalatest.freespec.AnyFreeSpec
// import org.scalatest.matchers.must.Matchers
// import chisel3.util.log2Ceil
// import chiseltest._

object Driver {
  def cyc (n:Int) (dut: Ps2Detector) = {
    assert(n >= 1)
    dut.clock.step(n-1)
    dut.io.ps2Clk.poke(0)
    dut.clock.step(n)
    dut.io.ps2Clk.poke(1)
    dut.clock.step(1)
  }

  def drv (ps2Val: List[Int], dataRate: Int) = { 
    dut: Ps2Detector =>
    // Postive edge clock
    dut.io.ps2Clk.poke(1)
    dut.clock.step(4)
    dut.io.acqOut.poke(0)
    dut.clock.step(dataRate)
    for (ps2v <- ps2Val) {
      dut.io.ps2Dat.poke(0)
      cyc (dataRate) (dut)

      var parity = 0x1
      for (i <- 0 until 8) {
        val bit = (ps2v >> i) & 0x1
        parity = parity ^ bit
        dut.io.ps2Dat.poke(bit)
        cyc (dataRate) (dut)
      }
      // println(f"Parity = $parity%x")
      dut.io.ps2Dat.poke(parity & 0x1)
      cyc (dataRate) (dut)
      dut.io.ps2Dat.poke(1)
      cyc (dataRate) (dut)
      // assert(dut.io.outEn.peekValue().asBigInt == 1)
      dut.io.outEn.expect(true)
    }

    dut.clock.step(dataRate)
    for (ps2v <- ps2Val) {
      // assert(dut.io.outEn.peekValue().asBigInt == 1)
      dut.io.outEn.expect(true)
      dut.io.acqOut.poke(1)
      // NOTE: Only delay one cycle
      dut.clock.step()
      // val outData = dut.io.outDt.peekValue().asBigInt
      val outData = dut.io.outDt.peekInt()
      dut.io.acqOut.poke(0)
      dut.clock.step()
      println(f"\tExpect ${ps2v}%x, get ${outData}%x")
      dut.io.outDt.expect(ps2v)
      // assert(outData == ps2v)
    }
  }
}

class Ps2DecoderSpec extends AnyFlatSpec with ChiselScalatestTester {
  "PS2 decoder" should "pass" in {
    test (new Ps2Detector()).withAnnotations(Seq(
      WriteVcdAnnotation
    )) { dut =>
      val inputDataVal = List(0x0f, 0x1f, 0xAA)
      Driver.drv(inputDataVal, 6) (dut)
    }
  }
}
