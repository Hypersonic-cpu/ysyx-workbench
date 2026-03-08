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

  val ifs      = Module(new FetchStage(resetVector, 3))
  val ids      = Module(new DecodeStage)
  val exs      = Module(new ExecuteStage)
  val lss      = Module(new MemoryStage)
  val wbs      = Module(new WrBackStage)
  val reg      = Module(new RegFile)
  val raw      = Module(new RAWDet)
  val mul      = Module(new IntMultiplier)
  val div      = Module(new IntDivider)
  val dispatch = Module(new Dispatcher) // Timing
  val collect  = Module(new Collector)  // Comb
  val flush    = Module(new FlushCtrl)

  /** Flush wiring */

  // EXU -> FlushCtrl
  exs.io.brDet <> flush.io.brDet
  exs.io.brInfo <> flush.io.brInfo
  flush.io.exFired := exs.io.outFire

  // WBU -> FlushCtrl
  val mtvecWire = reg.io.mtvecOut
  flush.io.wbExcpFlush  := wbs.io.excpFlushOut
  flush.io.wbExcpTarget := mtvecWire

  // FlushCtrl -> IFU
  flush.io.toFetch <> ifs.io.fromEx
  ifs.io.wbExcp       := flush.io.ifWbExcp
  ifs.io.wbExcpTarget := flush.io.ifWbExcpTarget

  // FlushCtrl -> IDU (always accept flush)
  flush.io.toIDU.ready := true.B
  ids.io.flush         :=
    flush.io.toIDU.valid && flush.io.toIDU.bits
  ids.io.excpFlush     := flush.io.excpFlush

  // FlushCtrl -> EXU
  exs.io.flush     := flush.io.exFlush
  exs.io.excpFlush := flush.io.excpFlush

  // FlushCtrl -> Dispatcher
  dispatch.io.pipeFlush := flush.io.pipeFlush
  dispatch.io.excpFlush := flush.io.excpFlush

  // FlushCtrl -> MUL / DIV
  mul.io.flush := flush.io.pipeFlush
  div.io.flush := flush.io.pipeFlush

  // FlushCtrl -> LSU
  lss.io.excpFlush := flush.io.excpFlush

  /** Data paths */

  // IF -> ID (registered pipeline)
  BusConnect(ifs.io.out, ids.io.in, Pipeline)

  // fence.I flush
  BusConnect(ids.io.fenceI, ifs.io.fromId, Pipeline)

  // ID -> Dispatcher -> {ALU, MUL, DIV}
  ids.io.out <> dispatch.io.decodeSide
  BusConnect(dispatch.io.aluSide, exs.io.in, MultiCyc)
  BusConnect(dispatch.io.mulSide, mul.io.in, MultiCyc)
  BusConnect(dispatch.io.divSide, div.io.in, MultiCyc)

  // EX -> LS (registered pipeline)
  BusConnect(exs.io.out, lss.io.in, Pipeline)

  // LS -> Collector.aluSide (registered pipeline)
  // MUL / DIV -> Collector (direct)
  BusConnect(lss.io.out, collect.io.aluSide, Pipeline)
  BusConnect(mul.io.out, collect.io.mulSide, MultiCyc)
  BusConnect(div.io.out, collect.io.divSide, MultiCyc)

  // Collector -> WBU
  collect.io.wbSide <> wbs.io.in

  // Scoreboard: Dispatcher <-> Collector <-> IDU
  dispatch.io.sbClear := collect.io.sbClear
  ids.io.sbBusy       := dispatch.io.sbBusy

  /** Register file */
  wbs.io.toReg <> reg.io.fromWb
  wbs.io.mtvecIn := mtvecWire
  ids.io.toReg <> reg.io.fromId
  reg.io.toId <> ids.io.fromReg

  // RAW hazard detection
  raw.io.srcfw <> ids.io.fwdRes
  raw.io.decode <> ids.io.rawSrc
  RegDstPacket(
    exs.io.fwdDet,
    exs.io.in.bits.foward,
    raw.io.exsrd
  )
  RegDstPacket(
    lss.io.fwdDet,
    lss.io.in.bits.foward,
    raw.io.lssrd
  )
  RegDstPacket(
    wbs.io.fwdDet,
    wbs.io.in.bits.foward,
    raw.io.wbsrd
  )

  // Memory subsystem

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
        (x(31, 28) >= 0x4.U && x(31, 28) < 0x8.U) ||
        x(31, 28) >= 0xc.U
    def isCacheable(x: UInt): Bool =
      isFlash(x) || isPsram(x) || isSdram(x)
    def isFaultSlverr(x: UInt): Bool = x(31, 16) === 0x0a00.U
    def isFaultDecerr(x: UInt): Bool = x(31, 16) === 0x0b00.U

    val iFaultSlverr = Module(new device.FaultBox(slverr = true))
    val iFaultDecerr = Module(new device.FaultBox(slverr = false))

    val iSplit = Module(
      new AXIXBar(
        4,
        Seq(
          (x: UInt) => isCacheable(x),
          (x: UInt) => isDev(x),
          (x: UInt) => isFaultSlverr(x),
          (x: UInt) => isFaultDecerr(x)
        )
      )
    )
    ifs.io.iMem <> iSplit.io.host
    iSplit.io.devices(0) <> icache.io.cpuSide
    iSplit.io.devices(2) <> iFaultSlverr.io.port
    iSplit.io.devices(3) <> iFaultDecerr.io.port
    icache.io.flushAll := RegNext(
      ids.io.fenceI.bits && ids.io.fenceI.valid
    )

    val dFaultSlverr = Module(new device.FaultBox(slverr = true))
    val dFaultDecerr = Module(new device.FaultBox(slverr = false))

    val dSplit = Module(
      new AXIXBar(
        5,
        Seq(
          (x: UInt) => isCacheable(x),
          (x: UInt) => isDev(x),
          (x: UInt) => isClint(x),
          (x: UInt) => isFaultSlverr(x),
          (x: UInt) => isFaultDecerr(x)
        )
      )
    )
    lss.io.dMem <> dSplit.io.host
    dSplit.io.devices(2) <> clint.io.port
    dSplit.io.devices(3) <> dFaultSlverr.io.port
    dSplit.io.devices(4) <> dFaultDecerr.io.port

    if (GlbCtrl.hasDCache) {
      val l1d = Module(new dCache(this.l1dConf))
      l1d.io.cpuSide <> dSplit.io.devices(0)
      l1d.io.flushAll := wbs.io.fenceI
      val fenceOnce = RegInit(false.B)
      when(ids.io.fenceI.bits && ids.io.fenceI.valid) {
        fenceOnce := true.B
      }
        .elsewhen(
          fenceOnce && RegNext(l1d.io.flushing) && !l1d.io.flushing
        ) { fenceOnce := false.B }
      ifs.io.fromLs := !fenceOnce
      val arbiter   = Module(new AXIArbiter(4))
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
      val faultSlverr =
        Module(new device.FaultBox(slverr = true))
      val dSplit      = Module(
        new AXIXBar(
          4,
          Seq(
            (x: UInt) => (x >= 0x8000_0000L.U),
            (x: UInt) => (x >= 0x0f00_0000L.U && x < 0x8000_0000L.U),
            (x: UInt) => (x >= 0x0200_0000L.U && x <= 0x0201_0000L.U),
            (x: UInt) => (x(31, 16) === 0x0a00.U)
          )
        )
      )
      dSplit.io.host <> lss.io.dMem
      dSplit.io.devices(2) <> clint.io.port
      dSplit.io.devices(3) <> faultSlverr.io.port
      val l1d         = Module(new dCache(this.l1dConf))
      l1d.io.cpuSide <> dSplit.io.devices(0)
      l1d.io.flushAll := wbs.io.fenceI
      val fenceOnce = RegInit(false.B)
      when(ids.io.fenceI.bits && ids.io.fenceI.valid) {
        fenceOnce := true.B
      }
        .elsewhen(
          fenceOnce && RegNext(
            l1d.io.flushing
          ) && !l1d.io.flushing
        ) { fenceOnce := false.B }
      ifs.io.fromLs := !fenceOnce

      val locXbar      = Module(
        new AXIXBar(
          3,
          Seq(
            (x: UInt) =>
              (x(31, 16) =/= 0x0a00.U &&
                x(31, 16) =/= 0x0b00.U),
            (x: UInt) => (x(31, 16) === 0x0a00.U),
            (x: UInt) => (x(31, 16) === 0x0b00.U)
          )
        )
      )
      val iFaultSlverr =
        Module(new device.FaultBox(slverr = true))
      val iFaultDecerr =
        Module(new device.FaultBox(slverr = false))
      locXbar.io.devices(1) <> iFaultSlverr.io.port
      locXbar.io.devices(2) <> iFaultDecerr.io.port

      val arbiter = Module(new AXIArbiter(3))
      arbiter.io.hosts(0) <> icache.io.memSide
      arbiter.io.hosts(1) <> l1d.io.memSide
      arbiter.io.hosts(2) <> dSplit.io.devices(1)
      locXbar.io.host <> arbiter.io.device

      val pMem = Module(new PMemBox)
      pMem.io.master <> locXbar.io.devices(0)
      io.master := DontCare
    } else {
      ifs.io.fromLs := true.B
      val faultSlverr =
        Module(new device.FaultBox(slverr = true))
      val dSplit      = Module(
        new AXIXBar(
          3,
          Seq(
            (x: UInt) => (x >= 0x0f00_0000L.U && x <= 0xffff_ffffL.U),
            (x: UInt) => (x >= 0x0200_0000L.U && x <= 0x0201_0000L.U),
            (x: UInt) => (x(31, 16) === 0x0a00.U)
          )
        )
      )
      dSplit.io.host <> lss.io.dMem
      dSplit.io.devices(1) <> clint.io.port
      dSplit.io.devices(2) <> faultSlverr.io.port

      val locXbar      = Module(
        new AXIXBar(
          3,
          Seq(
            (x: UInt) =>
              (x(31, 16) =/= 0x0a00.U &&
                x(31, 16) =/= 0x0b00.U),
            (x: UInt) => (x(31, 16) === 0x0a00.U),
            (x: UInt) => (x(31, 16) === 0x0b00.U)
          )
        )
      )
      val iFaultSlverr =
        Module(new device.FaultBox(slverr = true))
      val iFaultDecerr =
        Module(new device.FaultBox(slverr = false))
      locXbar.io.devices(1) <> iFaultSlverr.io.port
      locXbar.io.devices(2) <> iFaultDecerr.io.port

      val arbiter = Module(new AXIArbiter(2))
      arbiter.io.hosts(0) <> icache.io.memSide
      arbiter.io.hosts(1) <> dSplit.io.devices(0)
      locXbar.io.host <> arbiter.io.device

      val pMem = Module(new PMemBox)
      pMem.io.master <> locXbar.io.devices(0)
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
    dontTouch(mul.io)
    dontTouch(div.io)
    dontTouch(dispatch.io)
    dontTouch(collect.io)
    dontTouch(flush.io)
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
