package rvProc

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import circt.stage.FirtoolOption

object IntCvt {
  def twosC (x:Int) = (0xffffffffL ^ x) + 1L
}

class rvCoreSpec extends AnyFlatSpec with ChiselScalatestTester {
  "rvCore" should "pass Addi" in {
    test (new rvProc.rvCore("/mnt/hgfs/Arch-PA/ysyx-workbench/npc/rvproc/prog-rom/addi.hex"))
      .withAnnotations(Seq(
      WriteVcdAnnotation
    )) { dut =>
      dut.io.outPC.expect(0)

      dut.io.regPin.poke(5)  // t0
      dut.clock.step()
      dut.io.regPrb.expect(1)
      dut.io.outPC.expect(4)

      dut.io.regPin.poke(6)  // t1
      dut.clock.step()
      dut.io.regPrb.expect(2047)
      dut.io.outPC.expect(8)

      dut.io.regPin.poke(7)  // t2
      dut.clock.step()
      dut.io.regPrb.expect(IntCvt.twosC(1))
      dut.io.outPC.expect(0xc)

      dut.io.regPin.poke(8)  // s1
      dut.clock.step()
      dut.io.regPrb.expect(IntCvt.twosC(2048))
      dut.io.outPC.expect(0x10)

      dut.io.regPin.poke(0)  // zero
      dut.clock.step()
      dut.io.regPrb.expect(0)
      ()
    }
  }

  "rvCore" should "pass Jalr" in {
    test (new rvProc.rvCore("/mnt/hgfs/Arch-PA/ysyx-workbench/npc/rvproc/prog-rom/jalr.hex"))
      .withAnnotations(Seq(
        WriteVcdAnnotation
      )
    ) { dut =>
      dut.clock.step(3)
      dut.io.outPC.expect(0xc)

      dut.io.regPin.poke(1)
      dut.clock.step(1)
      dut.io.outPC.expect(0x20)
      dut.io.regPrb.expect(0x10)

      dut.clock.step(3)
      // dut.io.outPC.expect(0x10)
      
      dut.io.regPin.poke(7)
      dut.clock.step(2)
      dut.io.regPrb.expect(99)
    }
  }
}
