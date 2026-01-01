package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert
import rvproc.axi4._
import rvproc.axi4.AXI.RespStatus._
import rvproc.axi4.AXI.BurstOpts._
import rvproc.pmu.FetchPMU
import BitMath._

class HazardDet extends Module {
  val io = IO(new Bundle {
  })
}
