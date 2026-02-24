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
import BitMath._
import rvproc.cache.iCacheConf
import rvproc.cache.iCache
import rvproc.AnsiColor.ColorString._
import rvproc.AnsiColor.ColorString

class rvCore(
  isSoc:   Boolean,
  l1iConf: iCacheConf = iCacheConf(32, 1024, 16, 1))
    extends Module {
  val io = IO(new Bundle {
    val interrupt = Input(Bool())
    val master    = new AXIBus
    val slave     = Flipped(new AXIBus)
  })

  val resetVector = if (isSoc) 0x3000_0000L else 0x8000_0000L

  val ifs = Module(new FetchStage(resetVector))
  val ids = Module(new DecodeStage)
  val exs = Module(new ExecuteStage)
  val lss = Module(new MemoryStage)
  val wbs = Module(new WrBackStage)
  val reg = Module(new RegFile)
  val raw = Module(new RAWDet)

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

  val clint  = Module(new CLINT)
  val icache = Module(
    new cache.iCache(this.l1iConf)
  )

  if (isSoc) {
    println("=== FULL SoC MODE ===".green)

    /**       IFU                    LSU
      *     *--^--* IF Splitter    *--^--*-----* LS Splitter
      * l1i[ ]    | bypass     l1d[ ]    | dev * CLINT
      *     |     |                |     |
      *     *-----*--------*-------*-----*
      *                    | Offchip
      */

    def isFlash(x: UInt): Bool = x(31, 28) === 0x3.U
    def isPsram(x: UInt): Bool =
      x(31, 28) === 0x8.U || x(31, 28) === 0x9.U
    def isSdram(x: UInt): Bool =
      x(31, 28) === 0xa.U || x(31, 28) === 0xb.U
    def isClint(x: UInt): Bool = x(31, 16) === 0x0200.U
    def isDev(x: UInt): Bool =
      x(31, 24) === 0x0f.U ||                              // SRAM
        // UArt and SPI
        (x(31, 16) === 0x1000.U || x(31, 16) === 0x1001.U) ||
        x(31, 12) === 0x2000_0.U ||                        // MROM
        (x(31, 16) >= 0x2100.U && x(31, 16) < 0x2120.U) || // VGA
        (x(31, 28) >= 0x4.U && x(31, 16) < 0x8.U) ||       // Chiplink
        x(31, 28) >= 0xc.U

    val iSplit = Module(
      new AXIXBar(
        2,
        Seq(
          (x: UInt) => isFlash(x) || isPsram(x) || isSdram(x),
          // PC should not reach here. Raise assert failure in XBar
          (x: UInt) => false.B && isDev(x)
        )
      )
    )
    iSplit.io.devices(0) <> icache.io.cpuSide

    val dSplit = Module(
      new AXIXBar(
        3,
        Seq(
          // Cacheable
          (x: UInt) => isFlash(x) || isPsram(x) || isSdram(x),
          (x: UInt) => isDev(x),
          (x: UInt) => isClint(x)
        )
      )
    )
    dSplit.io.devices(2) <> clint.io.port
    // No CLINT memSide port

    val arbiter = Module(new AXIArbiter(4))
    AXIPortPassing(io.master, arbiter.io.device)
    arbiter.io.hosts(0) <> icache.io.memSide
    arbiter.io.hosts(1) <> iSplit.io.devices(1)
    arbiter.io.hosts(2) <> dSplit.io.devices(0) // or StoreBuf
    arbiter.io.hosts(3) <> dSplit.io.devices(1)
  } else {
    println("=== NPC MODE ===".yellow)

    /** IFU           LSU
      *  |             |
      * l1i$          StBuf
      *  *------*------*
      *         | Arbiter
      *  *------^------- XBar
      *  | CLINT       | PMem (simulate)
      */

    // val arbiter = Module(new AXIArbiter(2))
    // val locxbar = Module(
    //   new AXIXBar(
    //     2,
    //     Seq(
    //       AddrMap(0x0f00_0000L, 0xffff_ffffL, 0),
    //       AddrMap(0x0200_0000L, 0x0201_0000L, 1)
    //     )
    //   )
    // )
    //
    // val l1dPort = Module(new StoreBuffer(2))
    // l1dPort.io.cpuSide <> lss.io.dMem
    // l1dPort.io.empty <> ifs.io.fromLs
    //
    // val l1iPort = Module(
    //   new cache.iCache(this.l1iConf)
    // )
    // l1iPort.io.cpuSide <> ifs.io.iMem
    // l1iPort.io.flushAll := ids.io.fenceI.bits && ids.io.fenceI.valid
    //
    // arbiter.io.hosts(0) <> l1iPort.io.memSide
    // arbiter.io.hosts(1) <> l1dPort.io.memSide
    // arbiter.io.device <> locxbar.io.host
    //
    // val pMem = Module(new PMemBox)
    // locxbar.io.devices(1) <> clint.io.port
    // locxbar.io.devices(0) <> pMem.io.master
    // io.master := DontCare
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

class rvCoreWrapper(isSoc: Boolean, l1i: iCacheConf) extends Module {
  val io   = IO(new Bundle {
    val interrupt   = Input(Bool())
    val managerPort = new AXIBus
    val subordiPort = Flipped(new AXIBus)
  })
  val core = Module(new rvCore(isSoc, l1i))
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
