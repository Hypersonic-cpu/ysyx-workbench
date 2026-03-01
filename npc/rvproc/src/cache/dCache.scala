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

// Write-back, direct-mapped, non-pipelined data cache.
// Dirty bits in DFF. Tag/data via CacheArray (DFF or SRAM).
// Hit latency: 1 cycle. Eviction: burst write then burst read.
class dCache(conf: iCacheConf) extends Module {
  require(conf.assoc == 1, "Set assoc unimplemented")
  require(conf.dataBytes > 0, "dCache size must be > 0")
  val io = IO(new Bundle {
    val cpuSide  = Flipped(new AXIBus)
    val memSide  = new AXIBus
    val flushAll = Input(Bool())
    val flushing = Output(Bool())
  })

  conf.printConf()

  val tagArr   = Module(
    new CacheArray(conf.numSets, conf.tagBits)
  )
  val dataArr  = Module(
    new CacheArray(conf.numSets, conf.lineBytes * 8)
  )
  val validArr =
    RegInit(VecInit(Seq.fill(conf.numSets)(false.B)))
  val dirtyArr =
    RegInit(VecInit(Seq.fill(conf.numSets)(false.B)))

  val idle :: lookup :: evict :: filling :: flushing :: Nil = Enum(5)
  val state                                     = RegInit(idle)
  val nextState                                 = WireInit(idle)

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
  io.flushing          := state === flushing

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

  // Array read: issue in idle cycle when request arrives
  tagArr.io.raddr  := idxOf(
    Mux(cpuStore, io.cpuSide.aw.bits.addr, io.cpuSide.ar.bits.addr)
  )
  tagArr.io.ren    := state === idle && cpuReq
  dataArr.io.raddr := idxOf(
    Mux(cpuStore, io.cpuSide.aw.bits.addr, io.cpuSide.ar.bits.addr)
  )
  dataArr.io.ren   := state === idle && cpuReq

  // Tag compare in lookup cycle
  val tagRead  = tagArr.io.rdata
  val lineRead = dataArr.io.rdata
  tagHit := validArr(reqIdx) && tagRead === reqTag

  val isDirty   = validArr(reqIdx) && dirtyArr(reqIdx)
  val needEvict = !tagHit && isDirty

  // Line data as word vector
  val lineVec = VecInit.tabulate(conf.lineTrans)(i =>
    lineRead(
      (i + 1) * ISA.RegBits - 1,
      i * ISA.RegBits
    )
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

  // Array write defaults
  tagArr.io.wen    := false.B
  tagArr.io.waddr  := reqIdx
  tagArr.io.wdata  := reqTag
  dataArr.io.wen   := false.B
  dataArr.io.waddr := reqIdx
  dataArr.io.wdata := mergedLine.asUInt

  // Latch flushAll pulse so it isn't missed if dCache is busy
  when(io.flushAll)                       { flushPending := true.B }
  when(state === idle && flushPending)    { flushPending := false.B }

  val flushTrigger = io.flushAll || flushPending

  // FSM
  nextState := MuxLookup(state, idle)(
    Seq(
      idle -> Mux(
        flushTrigger,
        flushing,
        Mux(cpuReq, lookup, idle)
      ),
      lookup -> Mux(
        tagHit,
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
  state := nextState

  // idle: nothing extra
  // lookup: respond on hit or start eviction/fill
  when(state === lookup && tagHit) {
    when(reqIsStore) {
      dataArr.io.wen     := true.B
      dataArr.io.wdata   := mergedLine.asUInt
      dirtyArr(reqIdx)   := true.B
      io.cpuSide.b.valid := true.B
    }.otherwise {
      io.cpuSide.r.valid := true.B
    }
  }

  when(state === lookup && !tagHit) {
    evictTag := tagRead
    for (i <- 0 until conf.lineTrans) {
      evictLine(i) := lineVec(i)
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

  // Fill completion: write arrays and respond to CPU
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

    tagArr.io.wen    := true.B
    dataArr.io.wen   := true.B
    dataArr.io.wdata := finalLine.asUInt
    validArr(reqIdx) := true.B
    dirtyArr(reqIdx) := reqIsStore

    when(reqIsStore) {
      io.cpuSide.b.valid := true.B
    }.otherwise {
      io.cpuSide.r.valid     := true.B
      io.cpuSide.r.bits.data := filledLine(reqWord)
    }
  }

  // flushing: walk all sets, evict dirty ones then invalidate
  when(state === flushing) {
    when(!flushEvict && !flushReadPending) {
      when(validArr(flushIdx) && dirtyArr(flushIdx)) {
        tagArr.io.raddr  := flushIdx
        tagArr.io.ren    := true.B
        dataArr.io.raddr := flushIdx
        dataArr.io.ren   := true.B
        flushReadPending := true.B
        reqAddr          := (flushIdx << conf.offBits).asUInt
      }.otherwise {
        validArr(flushIdx) := false.B
        dirtyArr(flushIdx) := false.B
        when(flushIdx < (conf.numSets - 1).U) {
          flushIdx := flushIdx + 1.U
        }.otherwise {
          flushAllDone := true.B
        }
      }
    }.elsewhen(flushReadPending) {
      evictTag := tagArr.io.rdata
      for (i <- 0 until conf.lineTrans) {
        evictLine(i) := dataArr.io.rdata(
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
        validArr(flushIdx) := false.B
        dirtyArr(flushIdx) := false.B
        flushEvict         := false.B
        when(flushIdx < (conf.numSets - 1).U) {
          flushIdx := flushIdx + 1.U
        }.otherwise {
          flushAllDone := true.B
        }
      }
    }
  }

  when(io.flushAll && state === idle) {
    flushIdx         := 0.U
    flushReadPending := false.B
    flushEvict       := false.B
    flushAllDone     := false.B
  }

  if (debug) {
    dontTouch(tagHit)
    dontTouch(isDirty)
    dontTouch(needEvict)
    dontTouch(reqAddr)
    dontTouch(reqIsStore)
  }
}
