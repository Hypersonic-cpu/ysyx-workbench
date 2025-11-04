package rvProc.Test

import scala.sys.process._

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
  def dpiFiles() = Seq(
    file.Paths.get(dpiDir(), "simcalls.cc").toString(),
    file.Paths.get(dpiDir(), "pmemacc.cc").toString(),
  )
  def doLinkRam(s: String) = {
    val cmd_str = s"ln -sfn ${hexFile(s)} ${hexFile("meminit.hex")}"
    val cmd_res = cmd_str.!! // Raise RuntimeException if failed
    ()
  }
}

object VerilatorOpGen {
  def getFlags (noDbg: Boolean = false) = 
    VerilatorFlags(Seq("--trace-depth", "99", 
      "-y", PathCfg.dpiDir(), 
      "-CFLAGS", s"-I${PathCfg.vltDir()}") 
      ++ PathCfg.dpiFiles()
      ++ (if (noDbg) Seq("-DPRINTF_COND=0") else Nil) 
    )
}

class rvCoreSpec extends AnyFlatSpec with ChiselScalatestTester {
  "rvCore" should "pass Addi" in {
    PathCfg.doLinkRam("addi.hex")
    test (new rvProc.rvCore())
      .withAnnotations(Seq(
      // WriteVcdAnnotation,
      VerilatorBackendAnnotation,
      VerilatorOpGen.getFlags(true)
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

  "rvCore" should "pass Add" in {
    PathCfg.doLinkRam("add.hex")
    test (new rvProc.rvCore())
      .withAnnotations(Seq(
        // WriteVcdAnnotation,
        VerilatorBackendAnnotation,
        VerilatorOpGen.getFlags(true)
      )
    ) { dut =>
      dut.io.regPin.poke(1)
      dut.clock.step(1)
      dut.io.regPrb.expect(IntCvt.twosC(1))

      dut.io.regPin.poke(2)
      dut.clock.step(1)
      dut.io.regPrb.expect(1)

      dut.clock.step(1)

      dut.io.regPin.poke(10)
      dut.clock.step(1)
      dut.io.regPrb.expect(0)

      dut.io.regPin.poke(11)
      dut.clock.step(1)
      dut.io.regPrb.expect(2048)

      dut.io.regPin.poke(1)
      dut.clock.step(1)
      dut.io.regPrb.expect(IntCvt.twosC(2))

      dut.io.regPin.poke(12)
      dut.clock.step(2)
      dut.io.regPrb.expect(4094)
    }
  }

  "rvCore" should "pass Jalr" in {
    PathCfg.doLinkRam("jalr.hex")
    test (new rvProc.rvCore())
      .withAnnotations(Seq(
        // WriteVcdAnnotation,
        VerilatorBackendAnnotation,
        VerilatorOpGen.getFlags(true)
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

  "rvCore" should "pass Lui" in {
    PathCfg.doLinkRam("lui.hex")
    test (new rvProc.rvCore())
      .withAnnotations(Seq(
        // WriteVcdAnnotation,
        VerilatorBackendAnnotation,
        VerilatorOpGen.getFlags(true),
      )
    ) { dut =>
      dut.io.regPin.poke(1)
      dut.clock.step()
      dut.io.regPrb.expect(0)

      dut.io.regPin.poke(2)
      dut.clock.step()
      dut.io.regPrb.expect(IntCvt.twosC(4096))

      dut.io.regPin.poke(3)
      dut.clock.step()
      dut.io.regPrb.expect(0x12345000)

      dut.io.regPin.poke(4)
      dut.clock.step()
      dut.io.regPrb.expect(0x80000000L)

      dut.io.regPin.poke(5)
      dut.clock.step()
      dut.io.regPrb.expect(0x7ffff000)

      dut.clock.step()

      dut.io.regPin.poke(15)
      dut.clock.step()
      dut.io.regPrb.expect(0x7AAAA000)
    }
  }

  "rvCore" should "pass Load" in {
    PathCfg.doLinkRam("loads.hex")
    test (new rvProc.rvCore())
      .withAnnotations(Seq(
        // WriteVcdAnnotation,
        VerilatorBackendAnnotation,
        VerilatorOpGen.getFlags(true),
      )
    ) { dut =>

      dut.io.regPin.poke(5)
      dut.clock.step(2)
      dut.io.regPrb.expect(0x30L)

      dut.io.regPin.poke(6)
      dut.clock.step()
      dut.io.regPrb.expect(0xdeadbeefL)

      dut.io.regPin.poke(7)
      dut.clock.step()
      dut.io.regPrb.expect(0x1L)

      dut.io.regPin.poke(8)
      dut.clock.step()
      dut.io.regPrb.expect(0xffffffffL)

      dut.io.regPin.poke(9)
      dut.clock.step()
      dut.io.regPrb.expect(0x1L)

      dut.io.regPin.poke(10)
      dut.clock.step()
      dut.io.regPrb.expect(0xffffff80L)

      dut.io.regPin.poke(11)
      dut.clock.step()
      dut.io.regPrb.expect(0x00000080L)

      dut.io.regPin.poke(12)
      dut.clock.step()
      dut.io.regPrb.expect(0x0000007fL)
    }
  }

  "rvCore" should "pass Store" in {
    PathCfg.doLinkRam("stores.hex")
    test (new rvProc.rvCore())
      .withAnnotations(Seq(
        // WriteVcdAnnotation,
        VerilatorBackendAnnotation,
        VerilatorOpGen.getFlags(true),
      )
    ) { dut =>
      dut.clock.step(9)

      dut.io.regPin.poke(9)
      dut.clock.step()
      dut.io.regPrb.expect(0xdeadbeefL)

      dut.io.regPin.poke(10)
      dut.clock.step()
      dut.io.regPrb.expect(0xffffffffL)

      dut.io.regPin.poke(11)
      dut.clock.step()
      dut.io.regPrb.expect(0x0000007fL)
    }
  }

  "rvCore" should "exit at Ebreak" in {
    PathCfg.doLinkRam("ebreak.hex")
    test (new rvProc.rvCore())
      .withAnnotations(Seq(
        // WriteVcdAnnotation,
        VerilatorBackendAnnotation,
        VerilatorOpGen.getFlags(true),
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
        }
      }
    }
  }

  "rvCore" should "hit good trap sum.hex" in {
    PathCfg.doLinkRam("sum_v3.hex")
    test (new rvProc.rvCore())
      .withAnnotations(Seq(
        WriteVcdAnnotation,
        VerilatorBackendAnnotation,
        VerilatorOpGen.getFlags(false),
      )
    ) { dut =>
      dut.io.regPin.poke(10)
      try {
        dut.clock.step(5)
      } catch {
        case e: StopException => {
          println(s"Stop at cycle ${e.cycles}")
        }
      }
    }
  }
}
