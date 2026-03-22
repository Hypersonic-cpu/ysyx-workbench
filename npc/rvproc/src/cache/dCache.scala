package rvproc.cache

import chisel3._
import chisel3.util._
import chisel3.assert.Assert
import rvproc.axi4._
import rvproc.BitMath._
import rvproc.{ISA, Tp}
import rvproc.axi4.AXI.RespStatus.OKAY
import rvproc.axi4.AXI.BurstOpts._
import rvproc.GlbCtrl.debug
import rvproc.device.CacheArray
import rvproc.pmu.CacheSwPMU

// Write-back, set-associative, non-pipelined data cache.
// Dirty bits in DFF. Tag/data via CacheArray (DFF or SRAM).
// Hit latency: 1 cycle. Eviction: burst write then burst read.
// PLRU replacement policy (1, 2, or 4 ways).
class dCache(conf: CacheConf) extends Module {
  require(conf.dataBytes > 0, "dCache size must be > 0")
  val io = IO(new Bundle {
    val cpuSide  = Flipped(new AXIBus)
    val memSide  = new AXIBus
    val flushAll = Input(Bool())
    val flushing = Output(Bool())
  })

  conf.printConf()

  // Arrays per way
  val tagArrs  = Seq.tabulate(conf.assoc) { _ =>
    Module(new CacheArray(conf.numSets, conf.tagBits))
  }
  val dataArrs = Seq.tabulate(conf.assoc) { _ =>
    Module(new CacheArray(conf.numSets, conf.lineBytes * 8))
  }

  // Valid and dirty bits packed by set.
  // Each set keeps conf.assoc bits, bit i corresponds to way i.
  val validBits = RegInit(VecInit(Seq.fill(conf.numSets)(
    0.U(conf.assoc.W)
  )))
  val dirtyBits = RegInit(VecInit(Seq.fill(conf.numSets)(
    0.U(conf.assoc.W)
  )))

  // PLRU bits per set
  val plruArr = RegInit(VecInit(Seq.fill(conf.numSets)(
    0.U(PLRU.width(conf.assoc).W)
  )))

  val idle :: lookup :: evict :: filling :: flushing :: Nil = Enum(5)
  val state                                                 = RegInit(idle)
  val nextState                                             = WireInit(idle)

  def idxOf(x: UInt) = x(conf.idxBitHi, conf.idxBitLo)
  def tagOf(x: UInt) = x(conf.tagBitHi, conf.tagBitLo)
  def offOf(x: UInt) = x(conf.offBits - 1, 0)
  def blkOf(x: UInt) =
    x(conf.tagBitHi, conf.offBits) ## 0.U(conf.offBits.W)
  def ithOf(x: UInt) = x(conf.offBits - 1, ISA.WordShift)

  // Latched CPU request
  val reqAddr    = Reg(Tp.AddrType())
  val reqIsStore = Reg(Bool())
  val reqWData   = Reg(Tp.RegType())
  val reqWStrb   = Reg(UInt(4.W))
  val reqSize    = Reg(AXI.SizeType())
  val reqId      = Reg(AXI.IdType())
  val reqIdx     = idxOf(reqAddr)
  val reqTag     = tagOf(reqAddr)
  val reqWord    = ithOf(reqAddr)

  // Fill buffer for burst read
  val fillBuf =
    Reg(Vec(conf.lineTrans, Tp.RegType()))
  val fillPtr = RegInit(0.U(conf.lineTBits.W))

  // Eviction data latched from line read
  val evictLine =
    Reg(Vec(conf.lineTrans, Tp.RegType()))
  val evictTag  = Reg(UInt(conf.tagBits.W))
  val evictPtr  = RegInit(0.U(conf.lineTBits.W))
  val awSent    = RegInit(false.B)
  val wDone     = RegInit(false.B)
  val bRecvd    = RegInit(false.B)

  // Flush-all state (fence.i): walk all sets, evict dirty lines
  val flushIdx         = RegInit(0.U(log2Ceil(conf.numSets).W))
  val flushReadPending = RegInit(false.B)
  val flushEvict       = RegInit(false.B)
  val flushAllDone     = RegInit(false.B)
  val flushPending     = RegInit(false.B)
  val flushDone        = flushAllDone
  io.flushing := state === flushing

  // Tag compare result (valid in lookup cycle)
  val tagHit = Wire(Bool())

  // Accept new request only in idle
  val cpuLoad  = io.cpuSide.ar.valid && !io.cpuSide.aw.valid
  val cpuStore = io.cpuSide.aw.valid
  val cpuReq   = cpuLoad || cpuStore
  io.cpuSide.ar.ready := state === idle && !cpuStore && !io.flushAll && !flushPending
  io.cpuSide.aw.ready := state === idle && !io.flushAll && !flushPending
  io.cpuSide.w.ready  := state === idle && !io.flushAll && !flushPending

  // Latch request
  when(state === idle && cpuReq) {
    reqIsStore := cpuStore
    when(cpuStore) {
      reqAddr  := io.cpuSide.aw.bits.addr
      reqWData := io.cpuSide.w.bits.data
      reqWStrb := io.cpuSide.w.bits.strb
      reqSize  := io.cpuSide.aw.bits.size
      reqId    := io.cpuSide.aw.bits.id
    }.otherwise {
      reqAddr := io.cpuSide.ar.bits.addr
      reqSize := io.cpuSide.ar.bits.size
      reqId   := io.cpuSide.ar.bits.id
    }
  }

  // Victim way selection - store when transitioning to lookup
  val victimWay = Reg(UInt(log2Ceil(conf.assoc).W))
  val hitWay    = Reg(UInt(log2Ceil(conf.assoc).W))

  // Array read: issue to ALL ways in idle cycle when request arrives
  val readIdx = idxOf(
    Mux(cpuStore, io.cpuSide.aw.bits.addr, io.cpuSide.ar.bits.addr)
  )
  tagArrs.foreach { arr =>
    arr.io.raddr := readIdx
    arr.io.ren   := state === idle && cpuReq
  }
  dataArrs.foreach { arr =>
    arr.io.raddr := readIdx
    arr.io.ren   := state === idle && cpuReq
  }

  // Tag compare across all ways in lookup cycle
  val tagReads  = VecInit(tagArrs.map(_.io.rdata))
  val dataReads = VecInit(dataArrs.map(_.io.rdata))

  val wayHits = VecInit.tabulate(conf.assoc) { w =>
    tagReads(w) === reqTag && validBits(reqIdx)(w)
  }
  val anyHit = wayHits.asUInt.orR
  tagHit := anyHit

  // Determine victim way on miss: first invalid, else PLRU
  val invalids     = VecInit.tabulate(conf.assoc)(w => !validBits(reqIdx)(w))
  val hasInvalid   = invalids.asUInt.orR
  val firstInvalid = PriorityEncoder(invalids.asUInt)
  val plruVictim   = PLRU.getVictim(plruArr(reqIdx), conf.assoc)

  // Victim is dirty if valid and dirty
  val victimDirty = validBits(reqIdx)(victimWay) && dirtyBits(reqIdx)(victimWay)
  val needEvict   = !anyHit && victimDirty

  // Line data as word vector from hit way
  val hitData = Mux1H(wayHits, dataReads)
  val lineVec = VecInit.tabulate(conf.lineTrans)(i =>
    hitData((i + 1) * ISA.RegBits - 1, i * ISA.RegBits)
  )

  // Merge store data into line for store hit
  val strbMask   = Cat(
    Fill(8, reqWStrb(3)),
    Fill(8, reqWStrb(2)),
    Fill(8, reqWStrb(1)),
    Fill(8, reqWStrb(0))
  )
  val oldWord    = lineVec(reqWord)
  val mergedWord =
    (oldWord & ~strbMask) | (reqWData & strbMask)
  val mergedLine = Wire(Vec(conf.lineTrans, Tp.RegType()))
  for (i <- 0 until conf.lineTrans) {
    mergedLine(i) := Mux(
      i.U === reqWord,
      mergedWord,
      lineVec(i)
    )
  }

  // CPU response defaults
  io.cpuSide.r.valid     := false.B
  io.cpuSide.r.bits.data := lineVec(reqWord)
  io.cpuSide.r.bits.resp := OKAY
  io.cpuSide.r.bits.last := true.B
  io.cpuSide.r.bits.id   := reqId
  io.cpuSide.b.valid     := false.B
  io.cpuSide.b.bits.resp := OKAY
  io.cpuSide.b.bits.id   := reqId

  // Memory side defaults
  io.memSide.ar.valid      := false.B
  io.memSide.ar.bits.addr  := blkOf(reqAddr)
  io.memSide.ar.bits.len   := (conf.lineTrans - 1).U
  io.memSide.ar.bits.burst := INCR
  io.memSide.ar.bits.id    := reqId
  io.memSide.ar.bits.size  := 0x2.U
  io.memSide.r.ready       := false.B

  io.memSide.aw.valid      := false.B
  io.memSide.aw.bits.addr  :=
    evictTag ## reqIdx ## 0.U(conf.offBits.W)
  io.memSide.aw.bits.len   := (conf.lineTrans - 1).U
  io.memSide.aw.bits.burst := INCR
  io.memSide.aw.bits.id    := reqId
  io.memSide.aw.bits.size  := 0x2.U
  io.memSide.w.valid       := false.B
  io.memSide.w.bits.data   := evictLine(evictPtr)
  io.memSide.w.bits.strb   := "b1111".U
  io.memSide.w.bits.last   :=
    evictPtr === (conf.lineTrans - 1).U
  io.memSide.b.ready       := false.B

  // Array write defaults - per way
  tagArrs.foreach { arr =>
    arr.io.wen   := false.B
    arr.io.waddr := reqIdx
    arr.io.wdata := reqTag
  }
  dataArrs.foreach { arr =>
    arr.io.wen   := false.B
    arr.io.waddr := reqIdx
    arr.io.wdata := mergedLine.asUInt
  }

  // Latch flushAll pulse so it isn't missed if dCache is busy
  when(io.flushAll) { flushPending := true.B }
  when(state === idle && flushPending) { flushPending := false.B }

  val flushTrigger = io.flushAll || flushPending

  // FSM
  nextState := MuxLookup(state, idle)(
    Seq(
      idle     -> Mux(
        flushTrigger,
        flushing,
        Mux(cpuReq, lookup, idle)
      ),
      lookup   -> Mux(
        anyHit,
        idle,
        Mux(needEvict, evict, filling)
      ),
      evict    -> Mux(bRecvd, filling, evict),
      filling  -> Mux(
        io.memSide.r.valid && io.memSide.r.bits.last,
        idle,
        filling
      ),
      flushing -> Mux(flushDone, idle, flushing)
    )
  )
  state     := nextState

  // Select victim way and latch hit way when entering lookup
  when(state === idle && cpuReq) {
    victimWay := Mux(hasInvalid, firstInvalid, plruVictim)
    hitWay    := OHToUInt(wayHits)
  }

  // lookup: respond on hit or start eviction/fill
  when(state === lookup && anyHit) {
    val actualHitWay = OHToUInt(wayHits)
    val hitWayOH     = wayHits.asUInt
    plruArr(reqIdx) := PLRU.update(plruArr(reqIdx), actualHitWay, conf.assoc)
    when(reqIsStore) {
      for (w <- 0 until conf.assoc) {
        when(wayHits(w)) {
          dataArrs(w).io.wen   := true.B
          dataArrs(w).io.wdata := mergedLine.asUInt
        }
      }
      dirtyBits(reqIdx)              := dirtyBits(reqIdx) | hitWayOH
      io.cpuSide.b.valid             := true.B
    }.otherwise {
      io.cpuSide.r.valid := true.B
    }
  }

  // Victim way data for eviction
  val victimData = dataReads(victimWay)
  val victimVec  = VecInit.tabulate(conf.lineTrans)(i =>
    victimData((i + 1) * ISA.RegBits - 1, i * ISA.RegBits)
  )

  when(state === lookup && !anyHit) {
    evictTag := tagReads(victimWay)
    for (i <- 0 until conf.lineTrans) {
      evictLine(i) := victimVec(i)
    }
    when(needEvict) {
      evictPtr := 0.U
      awSent   := false.B
      wDone    := false.B
      bRecvd   := false.B
    }.otherwise {
      fillPtr := 0.U
    }
  }

  // evict: burst write dirty line then fill
  when(state === evict) {
    io.memSide.b.ready := true.B
    when(!awSent) {
      io.memSide.aw.valid := true.B
      io.memSide.w.valid  := true.B
      when(io.memSide.aw.fire) { awSent := true.B }
      when(io.memSide.w.fire) {
        evictPtr := evictPtr + 1.U
        when(io.memSide.w.bits.last) { wDone := true.B }
      }
    }.elsewhen(!wDone) {
      io.memSide.w.valid := true.B
      when(io.memSide.w.fire) {
        evictPtr := evictPtr + 1.U
        when(io.memSide.w.bits.last) { wDone := true.B }
      }
    }
    when(io.memSide.b.fire) {
      bRecvd  := true.B
      fillPtr := 0.U
    }
  }

  // filling: burst read new line
  when(state === filling) {
    io.memSide.ar.valid :=
      fillPtr === 0.U && !RegNext(io.memSide.ar.fire)
    io.memSide.r.ready  := true.B
    when(io.memSide.r.fire) {
      fillBuf(fillPtr) := io.memSide.r.bits.data
      fillPtr          := fillPtr + 1.U
    }
  }

  // Fill completion: write arrays to victim way and respond to CPU
  val fillDone = state === filling &&
    io.memSide.r.valid && io.memSide.r.bits.last
  when(fillDone) {
    // For store miss: merge store into filled line
    val filledLine = Wire(Vec(conf.lineTrans, Tp.RegType()))
    for (i <- 0 until conf.lineTrans) {
      val word = Mux(
        fillPtr === (conf.lineTrans - 1).U && i.U === fillPtr,
        io.memSide.r.bits.data,
        fillBuf(i)
      )
      filledLine(i) := word
    }

    val finalLine = Wire(Vec(conf.lineTrans, Tp.RegType()))
    when(reqIsStore) {
      val sm = Cat(
        Fill(8, reqWStrb(3)),
        Fill(8, reqWStrb(2)),
        Fill(8, reqWStrb(1)),
        Fill(8, reqWStrb(0))
      )
      for (i <- 0 until conf.lineTrans) {
        finalLine(i) := Mux(
          i.U === reqWord,
          (filledLine(i) & ~sm) | (reqWData & sm),
          filledLine(i)
        )
      }
    }.otherwise {
      for (i <- 0 until conf.lineTrans)
        finalLine(i) := filledLine(i)
    }

    // Write to victim way
    for (w <- 0 until conf.assoc) {
      when(victimWay === w.U) {
        tagArrs(w).io.wen    := true.B
        dataArrs(w).io.wen   := true.B
        dataArrs(w).io.wdata := finalLine.asUInt
      }
    }
    val victimWayOH = UIntToOH(victimWay, conf.assoc)
    validBits(reqIdx) := validBits(reqIdx) | victimWayOH
    dirtyBits(reqIdx) := Mux(
      reqIsStore,
      dirtyBits(reqIdx) | victimWayOH,
      dirtyBits(reqIdx) & ~victimWayOH
    )
    plruArr(reqIdx) := PLRU.update(plruArr(reqIdx), victimWay, conf.assoc)

    when(reqIsStore) {
      io.cpuSide.b.valid := true.B
    }.otherwise {
      io.cpuSide.r.valid     := true.B
      io.cpuSide.r.bits.data := filledLine(reqWord)
    }
  }

  // flushing: walk all sets and ways, evict dirty ones then invalidate
  val flushWay = RegInit(0.U(log2Ceil(conf.assoc).W))
  when(state === flushing) {
    val flushWayOH = UIntToOH(flushWay, conf.assoc)
    when(!flushEvict && !flushReadPending) {
      // Check if current way at current set is dirty
      val wayDirty = validBits(flushIdx)(flushWay) && dirtyBits(flushIdx)(flushWay)
      when(wayDirty) {
        tagArrs.zipWithIndex.foreach { case (arr, w) =>
          arr.io.raddr := flushIdx
          arr.io.ren   := w.U === flushWay
        }
        dataArrs.zipWithIndex.foreach { case (arr, w) =>
          arr.io.raddr := flushIdx
          arr.io.ren   := w.U === flushWay
        }
        flushReadPending := true.B
        reqAddr          := (flushIdx << conf.offBits).asUInt
      }.otherwise {
        validBits(flushIdx) := validBits(flushIdx) & ~flushWayOH
        dirtyBits(flushIdx) := dirtyBits(flushIdx) & ~flushWayOH
        // Move to next way or next set
        when(flushWay < (conf.assoc - 1).U) {
          flushWay := flushWay + 1.U
        }.otherwise {
          flushWay := 0.U
          when(flushIdx < (conf.numSets - 1).U) {
            flushIdx := flushIdx + 1.U
          }.otherwise {
            flushAllDone := true.B
          }
        }
      }
    }.elsewhen(flushReadPending) {
      val flushTagReads  = VecInit(tagArrs.map(_.io.rdata))
      val flushDataReads = VecInit(dataArrs.map(_.io.rdata))
      evictTag := flushTagReads(flushWay)
      val flushData = flushDataReads(flushWay)
      for (i <- 0 until conf.lineTrans) {
        evictLine(i) := flushData(
          (i + 1) * ISA.RegBits - 1,
          i * ISA.RegBits
        )
      }
      evictPtr         := 0.U
      awSent           := false.B
      wDone            := false.B
      bRecvd           := false.B
      flushReadPending := false.B
      flushEvict       := true.B
    }.otherwise {
      // flushEvict: burst write dirty line (reuse evict signals)
      io.memSide.b.ready := true.B
      when(!awSent) {
        io.memSide.aw.valid := true.B
        io.memSide.w.valid  := true.B
        when(io.memSide.aw.fire) { awSent := true.B }
        when(io.memSide.w.fire) {
          evictPtr := evictPtr + 1.U
          when(io.memSide.w.bits.last) { wDone := true.B }
        }
      }.elsewhen(!wDone) {
        io.memSide.w.valid := true.B
        when(io.memSide.w.fire) {
          evictPtr := evictPtr + 1.U
          when(io.memSide.w.bits.last) { wDone := true.B }
        }
      }
      when(io.memSide.b.fire) {
        validBits(flushIdx) := validBits(flushIdx) & ~flushWayOH
        dirtyBits(flushIdx) := dirtyBits(flushIdx) & ~flushWayOH
        flushEvict                   := false.B
        // Move to next way or next set
        when(flushWay < (conf.assoc - 1).U) {
          flushWay := flushWay + 1.U
        }.otherwise {
          flushWay := 0.U
          when(flushIdx < (conf.numSets - 1).U) {
            flushIdx := flushIdx + 1.U
          }.otherwise {
            flushAllDone := true.B
          }
        }
      }
    }
  }

  when(io.flushAll && state === idle) {
    flushIdx         := 0.U
    flushWay         := 0.U
    flushReadPending := false.B
    flushEvict       := false.B
    flushAllDone     := false.B
  }

  if (debug) {
    dontTouch(anyHit)
    dontTouch(victimDirty)
    dontTouch(needEvict)
    dontTouch(reqAddr)
    dontTouch(reqIsStore)

    val pmu = Module(new CacheSwPMU)
    pmu.io.clock   := clock
    pmu.io.reset   := reset
    pmu.io.req     := state === idle && cpuReq
    pmu.io.reqAddr := Mux(
      cpuStore,
      io.cpuSide.aw.bits.addr,
      io.cpuSide.ar.bits.addr
    )
    val respFire =
      io.cpuSide.r.valid || io.cpuSide.b.valid
    pmu.io.resp     := respFire
    pmu.io.respHit  := respFire &&
      (state === lookup && anyHit)
    pmu.io.respAddr := reqAddr
    pmu.io.id       := 1.U
  }
}
