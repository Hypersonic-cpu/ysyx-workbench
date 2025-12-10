package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert
import rvproc.axi4.AXILite
import rvproc.PortPassing.DriveDir
import rvproc.axi4.AXIPortPassing
// import chisel3.util.experimental.loadMemoryFromFileInline
// import firrtl.annotations.MemoryLoadFileType

object BitMath {
  implicit class UIntSignExtender(val i: UInt) extends AnyVal {
    def SExt(width: Int = ISA.RegBits):     UInt = {
      i.asSInt.pad(width).asUInt
    }
    def MSBU(idx: Int = 0):                 UInt = {
      val chosen = ISA.RegBits - 1 - idx
      i(chosen, chosen)
    }
    def MSB(idx: Int = 0):                  UInt = {
      val chosen = ISA.RegBits - 1 - idx
      i(chosen, chosen)
    }
    def UExt(width: Int = ISA.RegBits + 1): UInt = {
      i.pad(width)
    }
  }

  implicit class LogicPropagator(val p: Bool) extends AnyVal {
    def Implies(q:  Bool): Bool = (~p) || q
    def Excludes(q: Bool): Bool = p Implies (~q)
  }
}
import BitMath._

class rvCore() extends Module {
  val io = IO(new Bundle {
    val iMemPort = new AXILite()
    val dMemPort = new AXILite()
  })

  val ifs = Module(new FetchStage)
  val ids = Module(new DecodeStage)
  val exs = Module(new ExecuteStage)
  val lss = Module(new MemoryStage)
  val wbs = Module(new WrBackStage)
  val reg = Module(new RegFile)

  BusConnect(ids.io.toFetch, ifs.io.fromId)
  BusConnect(exs.io.toFetch, ifs.io.fromEx)
  BusConnect(ifs.io.out, ids.io.in)
  BusConnect(ids.io.out, exs.io.in)
  BusConnect(exs.io.out, lss.io.in)
  BusConnect(lss.io.out, wbs.io.in)
  BusConnect(wbs.io.toReg, reg.io.fromWb)
  BusConnect(wbs.io.toFetch, ifs.io.fromWb)

  // always_comb
  // BusConnect(ids.io.toReg, reg.io.fromId, BusType.SingleCyc)
  // BusConnect(reg.io.toId, ids.io.fromReg, BusType.SingleCyc)

  // val imemp = Wire(Flipped(new AXILite()))
  // val dmemp = Wire(Flipped(new AXILite()))

  ids.io.toReg <> reg.io.fromId
  reg.io.toId <> ids.io.fromReg

  AXIPortPassing(io.iMemPort, ifs.io.iMem)
  AXIPortPassing(io.dMemPort, lss.io.dMem)

  dontTouch(ifs.io)
  dontTouch(ids.io)
  dontTouch(exs.io)
  dontTouch(lss.io)
  dontTouch(wbs.io)
  dontTouch(reg.io)
}

class rvCoreSocSim() extends Module {
  val io       = IO(new Bundle {})
  val iMemDpic = Module(new PMemBox)
  val dMemDpic = Module(new PMemBox)
  iMemDpic.clock := clock
  iMemDpic.reset := reset
  dMemDpic.clock := clock
  dMemDpic.reset := reset
  val core     = Module(new rvCore)
  core.io.iMemPort <> iMemDpic.io.master
  core.io.dMemPort <> dMemDpic.io.master
  dontTouch(core.io)
}

class rvCoreWrapper() extends Module {
  val io     = IO(new Bundle {})
  val socSim = Module(new rvCoreSocSim)
  dontTouch(socSim.io)
}
