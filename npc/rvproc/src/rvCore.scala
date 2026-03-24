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
import rvproc.cache.CacheConf
import rvproc.cache.iCache
import rvproc.cache.dCache
import rvproc.AnsiColor.ColorString

/**
 * [[ ADDR RANGE ]]
 * CLINT	0x0200_0000~0x0200_ffff
 * SRAM	0x0f00_0000~0x0fff_ffff
 * UART16550	0x1000_0000~0x1000_0fff
 * SPI master	0x1000_1000~0x1000_1fff
 * GPIO	0x1000_2000~0x1000_200f
 * PS2	0x1001_1000~0x1001_1007
 * MROM	0x2000_0000~0x2000_0fff
 * VGA	0x2100_0000~0x211f_ffff
 * Flash	0x3000_0000~0x3fff_ffff
 * ChipLink MMIO	0x4000_0000~0x7fff_ffff
 * PSRAM	0x8000_0000~0x9fff_ffff
 * SDRAM	0xa000_0000~0xbfff_ffff
 * ChipLink MEM	0xc000_0000~0xffff_ffff
 * Reserved	其他
 */

class rvCore(
  isSoc:   Boolean,
  l1iConf: CacheConf = CacheConf(32, 1024, 16, 1),
  l1dConf: CacheConf = CacheConf(32, 1024, 16, 1))
    extends Module {
  val io = IO(new Bundle {
    val interrupt = Input(Bool())
    val master    = new AXIBus(true)
    val slave     = Flipped(new AXIBus(true))
  })

  val resetVector = if (isSoc) 0x3000_0000L else 0x8000_0000L

  val ifs      = Module(new FetchStage(resetVector, 7))
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
  mul.io.flush := flush.io.excpFlush
  div.io.flush := flush.io.excpFlush

  // FlushCtrl -> LSU
  lss.io.excpFlush := flush.io.excpFlush

  /** Data paths */

  // IF -> ID (registered pipeline, pass-through ready)
  // Must flush PipeReg when IDU is flushed, otherwise a
  // speculative instruction held during RAW stall survives
  // the 1-cycle flush pulse and commits on the next cycle.
  val iduFlush =
    (flush.io.toIDU.valid && flush.io.toIDU.bits) ||
      flush.io.excpFlush
  BusConnect(ifs.io.out, ids.io.in, PipeReg, iduFlush)

  // fence.I: registered, always-accept (rare, not perf-critical)
  ids.io.fenceI.ready := true.B
  ifs.io.fromId.valid := RegNext(ids.io.fenceI.fire, false.B)
  ifs.io.fromId.bits  := RegEnable(
    ids.io.fenceI.bits,
    ids.io.fenceI.fire
  )

  // ID -> Dispatcher -> {ALU, MUL, DIV}
  BusConnect(ids.io.out, dispatch.io.decodeSide, MultiCyc)
  BusConnect(dispatch.io.aluSide, exs.io.in, MultiCyc)

  // Register MUL/DIV input ready to break the
  // critical path from mul/div state registers
  // through scoreboard to dispatch.dispValid.
  // sbAnyBusy already prevents double-dispatch so
  // 1-cycle stale ready is safe.
  mul.io.in.valid           := dispatch.io.mulSide.valid
  mul.io.in.bits            := dispatch.io.mulSide.bits
  dispatch.io.mulSide.ready :=
    RegNext(mul.io.in.ready, true.B)

  div.io.in.valid           := dispatch.io.divSide.valid
  div.io.in.bits            := dispatch.io.divSide.bits
  dispatch.io.divSide.ready :=
    RegNext(div.io.in.ready, true.B)

  // EX -> LS (skid buffer, breaks backward ready chain)
  val skidV = RegInit(false.B)
  val skidB = Reg(new ExecuteToMemory)
  locally {
    val mainV = RegInit(false.B)
    val mainB = Reg(new ExecuteToMemory)
    exs.io.out.ready := !skidV
    lss.io.in.valid  := mainV
    lss.io.in.bits   := mainB
    when(flush.io.excpFlush) {
      mainV := false.B
      skidV := false.B
    }.elsewhen(lss.io.in.fire) {
      when(exs.io.out.fire) { mainB := exs.io.out.bits }
        .elsewhen(skidV) { mainB := skidB; skidV := false.B }
        .otherwise { mainV := false.B }
    }.elsewhen(exs.io.out.fire) {
      when(!mainV) {
        mainV := true.B; mainB := exs.io.out.bits
      }.otherwise { skidV := true.B; skidB := exs.io.out.bits }
    }
  }

  // LS -> Collector.aluSide (registered pipeline)
  // MUL / DIV -> Collector (direct)
  BusConnect(
    lss.io.out,
    collect.io.aluSide,
    PipeReg,
    flush.io.excpFlush
  )
  BusConnect(mul.io.out, collect.io.mulSide, MultiCyc)
  BusConnect(div.io.out, collect.io.divSide, MultiCyc)

  // In-order commit guard: track ALU instructions between
  // EXU output and Collector input. Block MUL/DIV commit
  // at Collector while older ALU instructions are draining.
  val aluInFlight = RegInit(0.U(3.W))
  val aluIncr     = exs.io.out.fire
  val aluDecr     = collect.io.aluSide.fire
  when(flush.io.excpFlush) {
    aluInFlight := 0.U
  }.otherwise {
    aluInFlight := aluInFlight +
      aluIncr.asUInt - aluDecr.asUInt
  }
  collect.io.pendingALU := aluInFlight > 0.U
  collect.io.excpFlush := flush.io.excpFlush

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
    exs.io.in.bits.forward,
    raw.io.exsrd
  )
  locally {
    val fw = Wire(new FwBundle)
    fw.valid := skidV
    fw.gprFw := skidB.forward.wbSel === WbSel.fromAlu
    fw.gprDt := skidB.aluOut
    RegDstPacket(fw, skidB.forward, raw.io.skidrd)
  }
  RegDstPacket(
    lss.io.fwdDet,
    lss.io.in.bits.forward,
    raw.io.lssrd
  )
  RegDstPacket(
    wbs.io.fwdDet,
    wbs.io.in.bits.forward,
    raw.io.wbsrd
  )

  // Memory subsystem

  val clint  = Module(new CLINT)
  val icache = Module(
    new cache.iCache(
      this.l1iConf,
      withPrefetch = GlbCtrl.withPrefetch
    )
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
      val dSplit = Module(
        new AXIXBar(
          3,
          Seq(
            (x: UInt) => (x >= 0x8000_0000L.U),
            (x: UInt) => (x >= 0x0f00_0000L.U && x < 0x8000_0000L.U),
            (x: UInt) => (x >= 0x0200_0000L.U && x <= 0x0201_0000L.U)
          )
        )
      )
      dSplit.io.host <> lss.io.dMem
      dSplit.io.devices(2) <> clint.io.port
      val l1d    = Module(new dCache(this.l1dConf))
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
            (x: UInt) => (x >= 0x0f00_0000L.U && x <= 0xffff_ffffL.U),
            (x: UInt) => (x >= 0x0200_0000L.U && x <= 0x0201_0000L.U)
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
    // Centralized per-cycle stall cause for PMU.
    // StallCause enum: NoStall=0 IfuStall=1 LsuStall=2 Branch=3 RAW=4
    // recovering: set on flush, cleared on first commit after flush
    // Tracks both IFU refetch and pipeline refill after misprediction
    val recovering = RegInit(false.B)
    when(wbs.io.in.fire && !iduFlush && !flush.io.excpFlush) {
      recovering := false.B
    }
    when(iduFlush || flush.io.excpFlush) {
      recovering := true.B
    }

    val iduRawStall = ids.io.rawStall.get
    val lsuStall    =
      skidV || (lss.io.in.valid && !lss.io.in.ready)

    val stallCause = Wire(UInt(8.W))
    when(wbs.io.in.fire) {
      stallCause := 0.U // NoStall
    }.elsewhen(lsuStall && !recovering) {
      // LSU stall on correct-path (not during recovery)
      stallCause := 2.U // LsuStall
    }.elsewhen(iduRawStall && !recovering) {
      // RAW stall on correct-path (not during recovery)
      stallCause := 4.U // RAW
    }.elsewhen(recovering) {
      // All non-commit cycles during recovery are mispred penalty
      // This includes IFU refetch, pipeline refill, and any stalls
      // on wrong-path instructions still draining
      stallCause := 3.U // BranchMispred
    }.otherwise {
      stallCause := 1.U // NoInst (genuine iCache miss, not mispred)
    }
    wbs.io.stallCauseIn.get := stallCause

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
  l1i:   CacheConf,
  l1d:   CacheConf)
    extends Module {
  val io   = IO(new Bundle {
    val interrupt   = Input(Bool())
    val managerPort = new AXIBus(true)
    val subordiPort = Flipped(new AXIBus(true))
  })
  val core = Module(new rvCore(isSoc, l1i, l1d))
  core.io.interrupt := io.interrupt
  AXIPortPassing(io.managerPort, core.io.master)
  AXIPortPassing(core.io.slave, io.subordiPort)
  dontTouch(core.io)
}
