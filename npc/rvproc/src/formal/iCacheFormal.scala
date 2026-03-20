package rvproc.formal

import chisel3._
import chisel3.util._
import rvproc._
import rvproc.axi4._
import rvproc.axi4.AXI.RespStatus
import rvproc.axi4.AXI.BurstOpts
import rvproc.cache.{iCache, CacheConf}

// ---------------------------------------------------------------------------
// Formal-verification wrapper for iCache.
//
// Goal: prove that cpuSide returns the SAME data as a direct memory lookup
//       (the "golden model") for every completed read transaction.
//
// Architecture:
//   +-------------+       +----------+
//   |  symbolic    |--ar->|  iCache  |--memSide->+----------+
//   |  requestor   |<--r--|  (DUT)   |<----------|  AXI mem |
//   +-------------+       +----------+           |  model   |
//                                                 +----------+
//                                                      |
//                 golden lookup <-----------------------+
//
// Key insight for formal: we can't use a Chisel Mem for the golden model
// because CIRCT optimises away Mems with no writes (they're dead logic).
// Instead, we *record* each AXI burst response into a golden register file
// as it flows through - both the DUT and our checker see the same data.
// When the DUT responds to the CPU, we compare against what was stored.
// ---------------------------------------------------------------------------

class iCacheFormal extends Module {
  // Test 2-way set-associative with small cache for formal tractability
  val conf = CacheConf(
    addrBits  = 32,
    dataBytes = 256,   // 256B total
    lineBytes = 16,    // 16B lines
    assoc     = 2      // 2-way -> 8 sets
  )

  val io = IO(new Bundle {
    val cpuReqAddr  = Input(Tp.AddrType())
    val cpuReqValid = Input(Bool())
    // AXI memory model data input - driven symbolically by the solver.
    // Each cycle during a burst, this provides the next word.
    val memRData    = Input(Tp.RegType())
    val cpuRespFire = Output(Bool())
    val cpuRespData = Output(Tp.RegType())
  })

  // === DUT ===================================================================
  val dut = Module(new iCache(conf))

  // === CPU-side stimulus ====================================================
  chisel3.assume(io.cpuReqAddr(1, 0) === 0.U)
  chisel3.assume(io.cpuReqAddr < conf.dataBytes.U)

  dut.io.cpuSide.ar.valid      := io.cpuReqValid
  dut.io.cpuSide.ar.bits.addr  := io.cpuReqAddr
  dut.io.cpuSide.ar.bits.size  := 2.U
  dut.io.cpuSide.ar.bits.len   := 0.U
  dut.io.cpuSide.ar.bits.burst := BurstOpts.FIXED
  dut.io.cpuSide.ar.bits.id    := 0.U
  dut.io.cpuSide.r.ready       := true.B
  dut.io.cpuSide.aw.valid      := false.B
  dut.io.cpuSide.aw.bits       := DontCare
  dut.io.cpuSide.w.valid       := false.B
  dut.io.cpuSide.w.bits.data   := 0.U
  dut.io.cpuSide.w.bits.strb   := 0.U
  dut.io.cpuSide.w.bits.last   := false.B
  dut.io.cpuSide.b.ready       := false.B
  dut.io.flushAll              := false.B

  // === AXI memory model =====================================================
  val sIdle :: sBurst :: Nil = Enum(2)
  val memState               = RegInit(sIdle)
  val burstBase              = Reg(UInt(32.W))
  val burstCnt               = RegInit(0.U(log2Ceil(conf.lineTrans).W))
  val burstLen               = Reg(UInt(8.W))

  dut.io.memSide.ar.ready := memState === sIdle

  dut.io.memSide.r.valid     := false.B
  dut.io.memSide.r.bits.data := io.memRData
  dut.io.memSide.r.bits.resp := RespStatus.OKAY
  dut.io.memSide.r.bits.last := false.B
  dut.io.memSide.r.bits.id   := 0.U

  dut.io.memSide.aw.ready := false.B
  dut.io.memSide.w.ready  := false.B
  dut.io.memSide.b.valid  := false.B
  dut.io.memSide.b.bits   := DontCare

  switch(memState) {
    is(sIdle) {
      when(dut.io.memSide.ar.valid) {
        memState  := sBurst
        burstBase := dut.io.memSide.ar.bits.addr
        burstLen  := dut.io.memSide.ar.bits.len
        burstCnt  := 0.U
      }
    }
    is(sBurst) {
      dut.io.memSide.r.valid     := true.B
      dut.io.memSide.r.bits.last := burstCnt === burstLen

      when(dut.io.memSide.r.ready) {
        when(burstCnt === burstLen) {
          memState := sIdle
          burstCnt := 0.U
        }.otherwise {
          burstCnt := burstCnt + 1.U
        }
      }
    }
  }

  // === Golden snapshot ======================================================
  // Record each AXI beat's (address -> data) into a golden register file.
  // We store only the current cache-line worth of words (4 words for 16B).
  // On a fill, we record which word each beat goes to and what data it
  // carried. Later, when the DUT responds, we look up the word.
  //
  // Storage: conf.dataBytes/4 words
  val NumWords = conf.dataBytes / 4
  val WAddrW   = log2Ceil(NumWords)
  val goldenRF = Reg(Vec(NumWords, UInt(32.W)))

  // Write golden RF during AXI burst beats
  val curWordAddr = (burstBase >> 2).asUInt + burstCnt
  when(dut.io.memSide.r.fire) {
    goldenRF(curWordAddr(WAddrW - 1, 0)) := io.memRData
  }

  // === Track request addresses ===============================================
  val addrFifo = Module(new Queue(Tp.AddrType(), 4))
  addrFifo.io.enq.valid := dut.io.cpuSide.ar.fire
  addrFifo.io.enq.bits  := io.cpuReqAddr
  addrFifo.io.deq.ready := dut.io.cpuSide.r.fire

  // === Core property ========================================================
  // When the DUT delivers a cpu-side response, data must match the
  // golden register file at the corresponding word address.
  when(dut.io.cpuSide.r.fire) {
    val respAddr   = addrFifo.io.deq.bits
    val wordIdx    = (respAddr >> 2).asUInt
    val goldenData = goldenRF(wordIdx(WAddrW - 1, 0))

    chisel3.assert(
      dut.io.cpuSide.r.bits.data === goldenData,
      "iCache returned wrong data!"
    )
  }

  chisel3.assert(
    addrFifo.io.enq.ready || !dut.io.cpuSide.ar.fire,
    "Address tracking FIFO overflow"
  )

  io.cpuRespFire := dut.io.cpuSide.r.fire
  io.cpuRespData := dut.io.cpuSide.r.bits.data
}

// ---------------------------------------------------------------------------
// Wrapper to prevent CIRCT from inlining/optimising away iCacheFormal.
// dontTouch(io) forces all ports to be preserved in the generated SV.
// ---------------------------------------------------------------------------

class iCacheFormalWrapper extends Module {
  val io = IO(new Bundle {
    val cpuReqAddr  = Input(Tp.AddrType())
    val cpuReqValid = Input(Bool())
    val memRData    = Input(Tp.RegType())
    val cpuRespFire = Output(Bool())
    val cpuRespData = Output(Tp.RegType())
  })

  val sys = Module(new iCacheFormal)
  sys.io.cpuReqAddr  := io.cpuReqAddr
  sys.io.cpuReqValid := io.cpuReqValid
  sys.io.memRData    := io.memRData
  io.cpuRespFire     := sys.io.cpuRespFire
  io.cpuRespData     := sys.io.cpuRespData

  dontTouch(io)
}
