package rvProc.Test

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import circt.stage.FirtoolOption
import chiseltest.simulator.VerilatorBackendAnnotation
import chiseltest.simulator.VerilatorFlags
import chiseltest.simulator.VerilatorLinkFlags

import java.nio.file
import chiseltest.simulator.VerilatorCFlags

object IntCvt {
  def twosC (x:Int) = (0xffffffffL ^ x) + 1L
}

object PathCfg {
  def workDir() = "/mnt/hgfs/Arch-PA/ysyx-workbench/npc/rvproc"
  def vltDir() =  "/mnt/hgfs/Arch-PA/ysyx-workbench/build-sim/rvproc/obj_dir"
  def hexDir() = workDir() + "/prog-rom"
  def dpiDir() = workDir() + "/dpic"
  def hexFile(s: String) = file.Paths.get(hexDir(), s).toString()
  def dpiFile() = file.Paths.get(dpiDir(), "simcalls.cc").toString()
}

object VerilatorOpGen {
  def getFlags () = 
    VerilatorFlags(Seq("--trace-depth", "99", 
      "-y", PathCfg.dpiDir(), 
      "-CFLAGS", s"-I${PathCfg.vltDir()}", PathCfg.dpiFile()))
}

class rvCoreSpec extends AnyFlatSpec with ChiselScalatestTester {
  "rvCore" should "pass Addi" in {
    test (new rvProc.rvCore(PathCfg.hexFile("addi.hex")))
      .withAnnotations(Seq(
      // WriteVcdAnnotation,
      VerilatorBackendAnnotation,
      VerilatorOpGen.getFlags()
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
    test (new rvProc.rvCore(PathCfg.hexFile("jalr.hex")))
      .withAnnotations(Seq(
        // WriteVcdAnnotation,
        VerilatorBackendAnnotation,
        VerilatorOpGen.getFlags()
      )
    ) { dut =>
      dut.clock.step(3)
      dut.io.outPC.expect(0xc)

      dut.io.regPin.poke(1)
      dut.clock.step(1)
      dut.io.outPC.expect(0x20)
      dut.io.regPrb.expect(0x10)

      dut.clock.step(3)
      dut.io.outPC.expect(0x10)

      dut.io.regPin.poke(7)
      dut.clock.step(2)
      dut.io.regPrb.expect(99)
    }
  }

  "rvCore" should "exit at Ebreak" in {
    test (new rvProc.rvCore(PathCfg.hexFile("ebreak.hex")))
      .withAnnotations(Seq(
        // WriteVcdAnnotation,
        VerilatorBackendAnnotation,
        VerilatorOpGen.getFlags(),
        // VerilatorOpGen.getCFlags()
      )
    ) { dut =>
      assertThrows[java.lang.RuntimeException] {
        dut.io.regPin.poke(10)
        try {
          var cnt = 0
          // while (cnt < 20 && !dut.imm)
          dut.clock.step(10)
        } catch {
          case e: StopException => {
            println(s"Stop at cycle ${e.cycles}")
          }
          // case e : Exception => 
          //   fail(s"Verilator exit with Exception ${e.getMessage()}")
          // case e : Throwable => 
          //   fail(s"Verilator exit with Throwable ${e.getMessage()}")
        }
      }
      ()
    }
  }
}
