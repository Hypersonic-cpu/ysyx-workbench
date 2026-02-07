package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert
import rvproc.axi4._
import rvproc.PortPassing.DriveDir
import rvproc.device.UART
import rvproc.device.CLINT
import rvproc.device.CLINTAddr
import rvproc.BusType._

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

class rvCore(isSoc: Boolean) extends Module {
  val io = IO(new Bundle {
    val interrupt = Input(Bool())
    val master    = new AXIBus
    val slave     = Flipped(new AXIBus)
  })

  val resetVector = if (isSoc) 0x3000_0000L else 0x8000_0000L

  val ifs   = Module(new FetchStage(resetVector))
  val ids   = Module(new DecodeStage)
  val exs   = Module(new ExecuteStage)
  val lss   = Module(new MemoryStage)
  val wbs   = Module(new WrBackStage)
  val reg   = Module(new RegFile)
  val clint = Module(new CLINT)
  val raw   = Module(new RAWDet)

  // exs.io.toFetch <> ifs.io.fromEx
  // exs.io.toDec <> ids.io.isFlush
  // TODO: brDet 和 exs.toFetch 功能类似, 考虑合并
  BusConnect(exs.io.brDet, exs.io.flush, Pipeline)
  BusConnect(exs.io.brDet, ids.io.flush, Pipeline)
  BusConnect(ids.io.fenceI, ifs.io.fromId, Pipeline)
  BusConnect(exs.io.toFetch, ifs.io.fromEx, Pipeline)
  BusConnect(ifs.io.out, ids.io.in, Pipeline)
  BusConnect(ids.io.out, exs.io.in, Pipeline)
  BusConnect(exs.io.out, lss.io.in, Pipeline)
  BusConnect(lss.io.out, wbs.io.in, Pipeline)
  wbs.io.toReg <> reg.io.fromWb
  // wbs.io.toFetch <> ifs.io.fromWb

  ids.io.toReg <> reg.io.fromId
  ids.io.fenceI.ready := true.B
  reg.io.toId <> ids.io.fromReg

  // To ID
  raw.io.srcfw <> ids.io.fwdRes
  // Sources
  raw.io.decode <> ids.io.rawSrc
  // raw.io.raw <> ids.io.rawRes
  RdPacket(exs.io.fwdDet, exs.io.in.bits.foward, raw.io.exsrd)
  RdPacket(lss.io.fwdDet, lss.io.in.bits.foward, raw.io.lssrd)
  RdPacket(wbs.io.fwdDet, wbs.io.in.bits.foward, raw.io.wbsrd)

  val dStrBuf = Module(new StoreBuffer(2))
  dStrBuf.io.empty <> ifs.io.fromLs
  lss.io.dMem <> dStrBuf.io.in

  val locxbar = Module(
    new AXIXBar(
      2,
      Seq(
        AddrMap(0x0f00_0000L, 0xffff_ffffL, 0),
        AddrMap(0x0200_0000L, 0x0201_0000L, 1)
      )
    )
  )

  if (isSoc) {
    val arbiter = Module(new AXIArbiter(2))
    // FIXME:
    // AXIPortPassing(io.master, arbiter.io.device)
    arbiter.io.hosts(0) <> ifs.io.iMem
    arbiter.io.hosts(1) <> dStrBuf.io.out
    // arbiter.io.hosts(1) <> lss.io.dMem
    arbiter.io.device <> locxbar.io.host

    locxbar.io.host <> dStrBuf.io.out
    locxbar.io.devices(1) <> clint.io.port
    AXIPortPassing(io.master, locxbar.io.devices(0))
  } else {

    /**  IFU       LSU
      *   |         |
      *   |       StBuf
      *   |         | XBar
      *   |       *----*
      *   |       |    |
      * DPI-C   CLINT DPI-C
      */

    val iMemBox = Module(new device.AXIConnBox)

    iMemBox.io.master <> ifs.io.iMem
    iMemBox.io.flush.id    := 0.U // inst cache
    iMemBox.io.flush.valid := ids.io.fenceI.valid && ids.io.fenceI.bits

    val dMemBox = Module(new device.AXIConnBox)
    dMemBox.io.master <> dStrBuf.io.out
    dMemBox.io.flush.id    := 1.U // data port
    dMemBox.io.flush.valid := false.B

    locxbar.io.host <> dStrBuf.io.out
    locxbar.io.devices(0) <> clint.io.port
    locxbar.io.devices(1) <> dMemBox.io.master

    // locxbar.io.host <> lss.io.dMem
    // locxbar.io.host <> dStrBuf.io.out
    // locxbar.io.devices(1) <> clint.io.port
    // locxbar.io.devices(0) <> dMemBox.io.master

    io.master := DontCare
  }

  if (GlbCtrl.debug) {
    dontTouch(ifs.io)
    dontTouch(ids.io)
    dontTouch(exs.io)
    dontTouch(lss.io)
    dontTouch(wbs.io)
    dontTouch(reg.io)
    dontTouch(io.master)
    dontTouch(io)
  }

  io.slave := DontCare
}

//
// class rvCoreSimEnv(resetVector: BigInt) extends Module {
//   val io   = IO(new Bundle {
//     val interrupt = Input(Bool())
//   })
//   val pmem = Module(new PMemBox)
//   val core = Module(new rvCore(resetVector))
//   pmem.io.master <> core.io.master
//   core.io.interrupt := io.interrupt
//   core.io.slave     := DontCare
// }

class rvCoreWrapper(isSoc: Boolean) extends Module {
  val io   = IO(new Bundle {
    val interrupt   = Input(Bool())
    val managerPort = new AXIBus
    val subordiPort = Flipped(new AXIBus)
  })
  val core = Module(new rvCore(isSoc))
  core.io.interrupt := io.interrupt
  AXIPortPassing(io.managerPort, core.io.master)
  AXIPortPassing(core.io.slave, io.subordiPort)
  dontTouch(core.io)
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
