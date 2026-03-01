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
import rvproc.cache.dCache
import rvproc.AnsiColor.ColorString

class rvCore(
  isSoc:   Boolean,
  l1iConf: iCacheConf = iCacheConf(32, 1024, 16, 1),
  l1dConf: iCacheConf = iCacheConf(32, 1024, 16, 1))
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

    def isFlash(x: UInt): Bool = x(31, 28) === 0x3.U
    def isPsram(x: UInt): Bool =
      x(31, 28) === 0x8.U || x(31, 28) === 0x9.U
    def isSdram(x: UInt): Bool =
      x(31, 28) === 0xa.U || x(31, 28) === 0xb.U
    def isClint(x: UInt): Bool = x(31, 16) === 0x0200.U
    def isDev(x: UInt):       Bool =
      x(31, 24) === 0x0f.U ||
        (x(31, 16) === 0x1000.U || x(31, 16) === 0x1001.U) ||
        x(31, 12) === 0x2000_0.U ||
        (x(31, 16) >= 0x2100.U && x(31, 16) < 0x2120.U) ||
        (x(31, 28) >= 0x4.U && x(31, 16) < 0x8.U) ||
        x(31, 28) >= 0xc.U
    def isCacheable(x: UInt): Bool =
      isFlash(x) || isPsram(x) || isSdram(x)

    val iSplit = Module(
      new AXIXBar(
        2,
        Seq(
          (x: UInt) => isCacheable(x),
          (x: UInt) => isDev(x)
        )
      )
    )
    ifs.io.iMem <> iSplit.io.host
    iSplit.io.devices(0) <> icache.io.cpuSide
    icache.io.flushAll := RegNext(
      ids.io.fenceI.bits && ids.io.fenceI.valid
    )

    val dSplit = Module(
      new AXIXBar(
        3,
        Seq(
          (x: UInt) => isCacheable(x),
          (x: UInt) => isDev(x),
          (x: UInt) => isClint(x)
        )
      )
    )
    lss.io.dMem <> dSplit.io.host
    dSplit.io.devices(2) <> clint.io.port

    val fenceIPulse = RegNext(ids.io.fenceI.bits && ids.io.fenceI.valid)

    if (GlbCtrl.hasDCache) {
      val l1d = Module(new dCache(this.l1dConf))
      l1d.io.cpuSide  <> dSplit.io.devices(0)
      l1d.io.flushAll := fenceIPulse
      ifs.io.fromLs   := !l1d.io.flushing
      val arbiter = Module(new AXIArbiter(4))
      AXIPortPassing(io.master, arbiter.io.device)
      arbiter.io.hosts(0) <> icache.io.memSide
      arbiter.io.hosts(1) <> iSplit.io.devices(1)
      arbiter.io.hosts(2) <> l1d.io.memSide
      arbiter.io.hosts(3) <> dSplit.io.devices(1)
    } else {
      ifs.io.fromLs := true.B
      val arbiter = Module(new AXIArbiter(4))
      AXIPortPassing(io.master, arbiter.io.device)
      arbiter.io.hosts(0) <> icache.io.memSide
      arbiter.io.hosts(1) <> iSplit.io.devices(1)
      arbiter.io.hosts(2) <> dSplit.io.devices(0)
      arbiter.io.hosts(3) <> dSplit.io.devices(1)
    }
  } else {
    println("=== NPC MODE ===".yellow)

    icache.io.cpuSide <> ifs.io.iMem
    icache.io.flushAll :=
      ids.io.fenceI.bits && ids.io.fenceI.valid

    if (GlbCtrl.hasDCache) {
      val dSplit = Module(
        new AXIXBar(
          3,
          Seq(
            (x: UInt) => (x >= 0x8000_0000L.U),
            (x: UInt) =>
              (x >= 0x0f00_0000L.U && x < 0x8000_0000L.U),
            (x: UInt) =>
              (x >= 0x0200_0000L.U && x <= 0x0201_0000L.U)
          )
        )
      )
      dSplit.io.host <> lss.io.dMem
      dSplit.io.devices(2) <> clint.io.port
      val l1d = Module(new dCache(this.l1dConf))
      l1d.io.cpuSide  <> dSplit.io.devices(0)
      l1d.io.flushAll := ids.io.fenceI.bits && ids.io.fenceI.valid
      ifs.io.fromLs   := !l1d.io.flushing
      val arbiter = Module(new AXIArbiter(3))
      arbiter.io.hosts(0) <> icache.io.memSide
      arbiter.io.hosts(1) <> l1d.io.memSide
      arbiter.io.hosts(2) <> dSplit.io.devices(1)
      val pMem = Module(new PMemBox)
      pMem.io.master <> arbiter.io.device
      io.master := DontCare
    } else {
      ifs.io.fromLs := true.B
      val dSplit = Module(
        new AXIXBar(
          2,
          Seq(
            (x: UInt) =>
              (x >= 0x0f00_0000L.U && x <= 0xffff_ffffL.U),
            (x: UInt) =>
              (x >= 0x0200_0000L.U && x <= 0x0201_0000L.U)
          )
        )
      )
      dSplit.io.host <> lss.io.dMem
      dSplit.io.devices(1) <> clint.io.port
      val arbiter = Module(new AXIArbiter(2))
      arbiter.io.hosts(0) <> icache.io.memSide
      arbiter.io.hosts(1) <> dSplit.io.devices(0)
      val pMem = Module(new PMemBox)
      pMem.io.master <> arbiter.io.device
      io.master := DontCare
    }
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

class rvCoreWrapper(
  isSoc: Boolean,
  l1i:   iCacheConf,
  l1d:   iCacheConf)
    extends Module {
  val io   = IO(new Bundle {
    val interrupt   = Input(Bool())
    val managerPort = new AXIBus
    val subordiPort = Flipped(new AXIBus)
  })
  val core = Module(new rvCore(isSoc, l1i, l1d))
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
