package rvproc.test

import scala.sys
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

class rvCoreAm extends AnyFlatSpec with ChiselScalatestTester {
  "rvCore" should "pass am-kernel" in {
    test (new rvproc.rvCore())
      .withAnnotations(Seq(
      WriteVcdAnnotation,
      VerilatorBackendAnnotation,
      VerilatorOpGen.getFlags(false)
    )) { dut =>
      dut.clock.step(40)
      ()
    }
  }
}
