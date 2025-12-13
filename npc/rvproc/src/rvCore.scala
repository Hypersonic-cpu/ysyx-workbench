package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert
import rvproc.axi4._
import rvproc.PortPassing.DriveDir
import rvproc.device.UART
import rvproc.device.CLINT
import rvproc.device.CLINTAddr
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
    val master = new AXIBus
    val slave  = Flipped(new AXIBus)
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

  io.slave := DontCare
}

// 设备	地址空间
// CLINT	0x0200_0000~0x0200_ffff
// SRAM	0x0f00_0000~0x0fff_ffff
// UART16550	0x1000_0000~0x1000_0fff
// SPI master	0x1000_1000~0x1000_1fff
// GPIO	0x1000_2000~0x1000_200f
// PS2	0x1001_1000~0x1001_1007
// MROM	0x2000_0000~0x2000_0fff
// VGA	0x2100_0000~0x211f_ffff
// Flash	0x3000_0000~0x3fff_ffff
// ChipLink MMIO	0x4000_0000~0x7fff_ffff
// PSRAM	0x8000_0000~0x9fff_ffff
// SDRAM	0xa000_0000~0xbfff_ffff
// ChipLink MEM	0xc000_0000~0xffff_ffff
// Reverse	其他
//
// class rvCoreSocSim() extends Module {
//   // val DRAMLo = 0x8000_0000L
//   // val DRAMHi = 0x8000_0000L
//   // val CLKLo  = 0x1000_0020L
//   // val SERIAL = 0x1000_0000L
//   val io = IO(new Bundle {})
//
//   val core    = Module(new rvCore)
//   val memDpic = Module(new PMemBox)
//   memDpic.clock := clock
//   memDpic.reset := reset
//   val uart  = Module(new UART)
//   val clint = Module(new CLINT)
//
//   val xbar = Module(
//     new AXIXBar(
//       4,
//       Seq(
//         AddrMap(0x8000_0000L, 0x8800_0000L, 0),
//         AddrMap(0x1000_0000L, 0x1000_0001L, 1),
//         AddrMap(CLINTAddr.Base, CLINTAddr.Base + CLINTAddr.Size, 2)
//       )
//     )
//   )
//   xbar.io.host <> core.io.master
//   xbar.io.devices(0) <> memDpic.io.master
//   xbar.io.devices(1) <> uart.io.port
//   xbar.io.devices(2) <> clint.io.port
//   xbar.io.devices(3) := DontCare
//   dontTouch(core.io)
// }
//
class rvCoreWrapper() extends Module {
  val io     = IO(new Bundle {
    val managerPort = new AXIBus
    val subordiPort = Flipped(new AXIBus)
  })
  val core   = Module(new rvCore)
  AXIPortPassing(io.managerPort, core.io.master)
  AXIPortPassing(core.io.slave, io.subordiPort)
  dontTouch(core.io)
  // val socSim = Module(new rvCoreSocSim)
  // dontTouch(socSim.io)
}
