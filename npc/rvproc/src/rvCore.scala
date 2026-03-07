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

  val ifs = Module(new FetchStage(resetVector, 2))
  val ids = Module(new DecodeStage)
  val exs = Module(new ExecuteStage)
  val lss = Module(new MemoryStage)
  val wbs = Module(new WrBackStage)
  val reg = Module(new RegFile)
  val raw = Module(new RAWDet)
  val mul = Module(new IntMultiplier)
  val div = Module(new IntDivider)

  // ── Scoreboard (tracks in-flight MUL/DIV rd) ────
  val scoreboard = RegInit(0.U(ISA.RegNum.W))

  // ── Pipeline connections (IF → ID → EX → LS) ───
  BusConnect(exs.io.brDet, exs.io.flush, Pipeline)
  BusConnect(exs.io.brDet, ids.io.flush, Pipeline)
  BusConnect(ids.io.fenceI, ifs.io.fromId, Pipeline)
  BusConnect(exs.io.toFetch, ifs.io.fromEx, Pipeline)
  BusConnect(ifs.io.out, ids.io.in, Pipeline)

  // ── Dispatch: IDU → EXU / MUL / DIV ─────────
  // Single pipeline register replacing the original
  // BusConnect(ids.io.out, exs.io.in, Pipeline)
  val dispValid = RegInit(false.B)
  val dispBits  = Reg(new DecodeToExecute)

  // Flush from EXU misprediction or WBU exception
  val exFlushWire =
    RegNext(exs.io.brDet.valid && exs.io.brDet.bits, false.B)
  val wbExcpFlush = wbs.io.excpFlushOut
  val pipeFlush   = exFlushWire || wbExcpFlush

  // Dispatch accepts when target is ready
  val isMulDisp = dispBits.isMul
  val isDivDisp = dispBits.isDiv
  val isMD      = isMulDisp || isDivDisp
  // When MUL/DIV is in-flight, block ALL dispatch so no
  // younger instruction can commit out of order.
  // Only one M-ext instruction may be in-flight at a time.
  val sbAnyBusy = scoreboard.orR
  val unitReady = Mux(
    isMulDisp,
    mul.io.in.ready,
    Mux(isDivDisp, div.io.in.ready, exs.io.in.ready)
  )
  val tgtReady  = unitReady && !sbAnyBusy

  // IDU → dispatch register (replaces BusConnect Pipeline)
  // Matches BusConnect semantics: register only updates when
  // downstream accepts (ids.io.out.ready). Never externally
  // flushed — EXU's internal self-loop flush (flushed /
  // regBrFlush) kills wrong-path instructions when they
  // eventually reach EXU, just like the original design.
  ids.io.out.ready := !dispValid || tgtReady
  when(ids.io.out.ready) {
    dispValid := ids.io.out.valid
    when(ids.io.out.valid) {
      dispBits := ids.io.out.bits
    }
  }

  // Dispatch → EXU (only non-M instructions)
  // No pipeFlush gate: EXU handles flush internally via
  // self-loop BusConnect(brDet → flush) and regBrFlush.
  // sbAnyBusy blocks younger EXU instructions from entering
  // the pipeline while MUL/DIV is in flight (in-order commit).
  exs.io.in.valid := dispValid && !isMD && !sbAnyBusy
  exs.io.in.bits  := dispBits

  // Dispatch → MUL (blocked when another M-ext in-flight)
  mul.io.in.valid       := dispValid && isMulDisp &&
    !pipeFlush && !sbAnyBusy
  mul.io.in.bits.rs1    := dispBits.rs1V
  mul.io.in.bits.rs2    := dispBits.rs2V
  mul.io.in.bits.op     := dispBits.mulDivOp
  mul.io.in.bits.rd     := dispBits.foward.gprRd
  mul.io.in.bits.foward := dispBits.foward
  mul.io.flush          := pipeFlush

  // Dispatch → DIV (blocked when another M-ext in-flight)
  div.io.in.valid       := dispValid && isDivDisp &&
    !pipeFlush && !sbAnyBusy
  div.io.in.bits.rs1    := dispBits.rs1V
  div.io.in.bits.rs2    := dispBits.rs2V
  div.io.in.bits.op     := dispBits.mulDivOp
  div.io.in.bits.rd     := dispBits.foward.gprRd
  div.io.in.bits.foward := dispBits.foward
  div.io.flush          := pipeFlush

  // ── Scoreboard set/clear ────────────────────────
  val sbSet   = Wire(UInt(ISA.RegNum.W))
  val sbClear = Wire(UInt(ISA.RegNum.W))

  // Set when MUL/DIV dispatched
  val dispFire = dispValid && isMD && tgtReady && !pipeFlush
  val dispRd   = dispBits.foward.gprRd
  sbSet := Mux(
    dispFire && dispRd.orR,
    1.U(ISA.RegNum.W) << dispRd,
    0.U
  )

  // ── EXU → LSU → (registered) → WBU merge ────
  BusConnect(exs.io.out, lss.io.in, Pipeline)

  // Pipeline register from LSU output
  val lsWbValid = Wire(Bool())
  val lsWbBits  = Wire(new MemoryToWrBack)
  val lsWbReady = Wire(Bool())
  lss.io.out.ready := lsWbReady
  lsWbValid        := RegEnable(lss.io.out.valid, lsWbReady)
  lsWbBits         := RegEnable(lss.io.out.bits, lsWbReady)

  // ── Writeback merge: DIV > MUL > EXU(LSU) ──────
  // Priority: DIV > MUL > LSU
  val divWins = div.io.out.valid
  val mulWins = mul.io.out.valid && !divWins
  val mdValid = divWins || mulWins

  // Construct MemoryToWrBack from MUL/DIV result
  val mdWbBits = Wire(new MemoryToWrBack)
  val mdFoward =
    Mux(divWins, div.io.out.bits.foward, mul.io.out.bits.foward)
  val mdResult =
    Mux(divWins, div.io.out.bits.result, mul.io.out.bits.result)
  mdWbBits.aluOut := mdResult
  mdWbBits.lsuOut := 0.U
  mdWbBits.foward := mdFoward
  // M-ext results use wbSel=fromAlu (gprdt = aluOut)

  // ── Writeback merge: LSU-path (older) > MUL/DIV ──────
  // In-order dispatch guarantees any instruction in lsWbValid
  // was dispatched before the in-flight MUL/DIV, so it must
  // commit first.  MUL/DIV result waits until the LSU path
  // drains.
  when(lsWbValid) {
    // Older EXU-path instruction commits first
    wbs.io.in.valid := true.B
    wbs.io.in.bits  := lsWbBits
    lsWbReady       := wbs.io.in.ready
  }.elsewhen(mdValid) {
    // MUL/DIV result commits (pipeline drained)
    wbs.io.in.valid := true.B
    wbs.io.in.bits  := mdWbBits
    lsWbReady       := false.B // stall LSU pipeline
  }.otherwise {
    wbs.io.in.valid := false.B
    wbs.io.in.bits  := lsWbBits
    lsWbReady       := wbs.io.in.ready
  }

  // MUL/DIV output handshake — wait for older LSU-path
  // instruction to drain before consuming MUL/DIV result.
  div.io.out.ready := divWins && wbs.io.in.ready && !lsWbValid
  mul.io.out.ready := mulWins && wbs.io.in.ready && !lsWbValid

  // Clear scoreboard on MUL/DIV writeback
  val mdWbFire = mdValid && wbs.io.in.ready
  val mdWbRd   =
    Mux(divWins, div.io.out.bits.rd, mul.io.out.bits.rd)
  sbClear := Mux(
    mdWbFire && mdWbRd.orR,
    1.U(ISA.RegNum.W) << mdWbRd,
    0.U
  )

  // Scoreboard update
  when(pipeFlush) {
    scoreboard := 0.U
  }.otherwise {
    scoreboard := (scoreboard | sbSet) & ~sbClear
  }
  // Include sbSet so IDU sees the scoreboard update on the
  // *same cycle* a MUL/DIV dispatches (register lags 1 cycle).
  ids.io.sbBusy := scoreboard | sbSet

  wbs.io.toReg <> reg.io.fromWb

  // Exception flush wiring
  val mtvecWire = reg.io.mtvecOut
  wbs.io.mtvecIn      := mtvecWire
  ifs.io.wbExcp       := wbs.io.excpFlushOut
  ifs.io.wbExcpTarget := mtvecWire
  ids.io.excpFlush    := wbs.io.excpFlushOut
  exs.io.excpFlush    := wbs.io.excpFlushOut
  exs.io.mtvecVal     := mtvecWire
  lss.io.excpFlush    := wbs.io.excpFlushOut

  ids.io.toReg <> reg.io.fromId
  ids.io.fenceI.ready := true.B
  reg.io.toId <> ids.io.fromReg

  // ── RAW hazard detection ───────────────────────
  raw.io.srcfw <> ids.io.fwdRes
  raw.io.decode <> ids.io.rawSrc
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
    dontTouch(io.master)
    dontTouch(io)
    dontTouch(scoreboard)
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
