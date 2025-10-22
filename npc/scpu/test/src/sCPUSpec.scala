package sCPU

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec


class Ps2DecoderSpec extends AnyFlatSpec with ChiselScalatestTester {
  "sCPU" should "exec successfully" in {
    test (new sCPU.sCPU("/mnt/hgfs/Arch-PA/ysyx-workbench/npc/scpu/prog-rom/Add1To10.sCPU.bin"))
      .withAnnotations(Seq(
      WriteVcdAnnotation
    )) { dut =>
      dut.io.regProbe.poke(2)
      var cyc = 0 
      while (cyc <= 70 && dut.io.outPC.peekInt() != 7) {
        dut.clock.step(1)
        cyc = cyc + 1
      }
      dut.io.dispEna.expect(1)
      dut.io.dispVal.expect(55)
      dut.clock.step(10)
      dut.io.outProbe.expect(55)
      dut.io.outPC.expect(8)
    }
  }
}
