package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert
import rvproc.axi4._
import rvproc.PortPassing.DriveDir
import rvproc.device.UART
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
    val master = new AXILite
  })

  val ifs = Module(new FetchStage)
  val ids = Module(new DecodeStage)
  val exs = Module(new ExecuteStage)
  val lss = Module(new MemoryStage)
  val wbs = Module(new WrBackStage)
  val reg = Module(new RegFile)

  val arbiter = Module(new AXIArbiter(2))

  BusConnect(ids.io.toFetch, ifs.io.fromId)
  BusConnect(exs.io.toFetch, ifs.io.fromEx)
  BusConnect(ifs.io.out, ids.io.in)
  BusConnect(ids.io.out, exs.io.in)
  BusConnect(exs.io.out, lss.io.in)
  BusConnect(lss.io.out, wbs.io.in)
  BusConnect(wbs.io.toReg, reg.io.fromWb)
  BusConnect(wbs.io.toFetch, ifs.io.fromWb)

  ids.io.toReg <> reg.io.fromId
  reg.io.toId <> ids.io.fromReg

  AXIPortPassing(io.master, arbiter.io.device)
  arbiter.io.hosts(0) <> ifs.io.iMem
  arbiter.io.hosts(1) <> lss.io.dMem

  dontTouch(ifs.io)
  dontTouch(ids.io)
  dontTouch(exs.io)
  dontTouch(lss.io)
  dontTouch(wbs.io)
  dontTouch(reg.io)
}

class rvCoreSocSim() extends Module {
  // val DRAMLo = 0x8000_0000L
  // val DRAMHi = 0x8000_0000L
  // val CLKLo  = 0x1000_0020L
  // val SERIAL = 0x1000_0000L
  val io = IO(new Bundle {})

  val core    = Module(new rvCore)
  val memDpic = Module(new PMemBox)
  memDpic.clock := clock
  memDpic.reset := reset
  val uart = Module(new UART)

  val xbar = Module(
    new AXIXBar(
      4,
      Seq(
        AddrMap(0x8000_0000L, 0x8800_0000L, 0),
        AddrMap(0x1000_0000L, 0x1000_0001L, 1),
        AddrMap(0x1000_0020L, 0x1000_0028L, 2)
      )
    )
  )
  xbar.io.host <> core.io.master
  xbar.io.devices(0) <> memDpic.io.master
  xbar.io.devices(1) <> uart.io.port
  xbar.io.devices(2) := DontCare
  xbar.io.devices(3) := DontCare
  dontTouch(core.io)
}

class rvCoreWrapper() extends Module {
  val io     = IO(new Bundle {})
  val socSim = Module(new rvCoreSocSim)
  dontTouch(socSim.io)
}
