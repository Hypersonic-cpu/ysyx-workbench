package rvProc

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec


class rvCoreSpec extends AnyFlatSpec with ChiselScalatestTester {
  "rvCore" should "exec successfully" in {
    test (new rvProc.rvCore("/mnt/hgfs/Arch-PA/ysyx-workbench/npc/rvproc/prog-rom/addi.hex"))
      .withAnnotations(Seq(
      WriteVcdAnnotation
    )) { dut =>
      dut.io.regPin.poke(5)  // t0
      dut.io.outPC.expect(0)
      dut.clock.step()
      dut.io.regPrb.expect(1)
      ()
    }
  }
}
