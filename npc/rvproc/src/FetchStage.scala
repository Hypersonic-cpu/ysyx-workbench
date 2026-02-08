package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert
import rvproc.axi4._
import rvproc.axi4.AXI.RespStatus._
import rvproc.axi4.AXI.BurstOpts._
import rvproc.pmu.FetchPMU
import BitMath._

class FetchStage(resetVector: BigInt, PipeDepth: Int = 3)
    extends Module {
  val io = IO(new Bundle {
    val out    = Decoupled(new FetchToDecode)
    val fromId = Flipped(Decoupled(Bool())) // fence.i
    val fromEx = Flipped(Decoupled(new ExecuteBackward))
    val fromLs = Input(Bool())              // store buffer empty
    // val fromWb = Flipped(Decoupled(new InstCommit))
    val iMem   = new AXIBus
  })

  val idle :: serve :: Nil = Enum(2)

  val iMem = io.iMem

  val brex       = io.fromEx.bits
  val brPending  = RegInit(false.B)
  val stBufEmpty = io.fromLs
  val fenceI     = io.fromId.valid && io.fromId.bits
  val fenceState = RegInit(false.B)
  fenceState := Mux(fenceState, io.fromLs, fenceI)
  val flushWire =
    (io.fromEx.valid && brex.take) || fenceI

  val pc     = RegInit(resetVector.U(ISA.RegBits.W))
  val nextPC = RegInit((resetVector + 4).U(ISA.RegBits.W))
  val lastPC = RegEnable(io.out.bits.pc, io.out.fire)

  val validBuf = Reg(Vec(PipeDepth + 1, Bool()))
  val pcBuf    = Reg(Vec(PipeDepth + 1, Tp.AddrType()))
  val instBuf  = Reg(Vec(PipeDepth + 1, Tp.InstType()))
  val headPtr  = RegInit(0.U(log2Ceil(PipeDepth + 1).W))
  val tailPtr  = RegInit(0.U(log2Ceil(PipeDepth + 1).W))
  // 能不能直接通过移动来 Handle 短途跳转?
  val toidPtr  = RegInit(0.U(log2Ceil(PipeDepth + 1).W))
  def iotaMod(a: UInt) = Mux(a === PipeDepth.U, 0.U, a + 1.U)

  val bufFull   = iotaMod(headPtr) === toidPtr
  dontTouch(bufFull)
  val instEmpty = toidPtr === tailPtr

  // Recv inst from iCache
  when(iMem.r.fire) {
    instBuf(tailPtr) := iMem.r.bits.data
    tailPtr          := iotaMod(tailPtr)
  }
  // Send fetch to iCache
  when(iMem.ar.fire) {
    // Overwrite by flushing logic
    validBuf(headPtr) := true.B
    pcBuf(headPtr)    := pc
    headPtr           := iotaMod(headPtr)
  }
  // Issue to IDU
  when(io.out.ready && !instEmpty) {
    validBuf(toidPtr) := false.B
    toidPtr           := iotaMod(toidPtr)
  }

  io.out.valid    := !instEmpty && !flushWire && validBuf(toidPtr)
  io.fromEx.ready := true.B
  io.fromId.ready := true.B

  iMem.ar.bits.addr  := pc
  iMem.ar.bits.size  := 0x2.U  // log2(4)
  iMem.ar.bits.len   := 0.U
  iMem.ar.bits.burst := INCR
  iMem.ar.bits.id    := 0.U
  iMem.ar.valid      := !reset.asBool && !bufFull && !fenceState && !flushWire
  iMem.r.ready       := true.B // io.out.ready
  iMem.aw.valid      := false.B
  iMem.aw.bits       := DontCare
  iMem.w.valid       := false.B
  iMem.w.bits        := DontCare
  iMem.b.ready       := false.B
  assert(~(iMem.b.valid), "Read only port")
  assert(
    iMem.r.valid Implies (iMem.r.bits.resp === OKAY),
    "Inst fetch error"
  )
  assert(
    iMem.r.valid Implies (iMem.r.bits.resp === OKAY),
    cf"Inst Fetch Failed, rresp = ${iMem.r.bits.resp}"
  )

  val brAbs = io.fromEx.valid && brex.brAbs
  val brRel = io.fromEx.valid && brex.brRel
  when(flushWire) {
    for (i <- 0 to PipeDepth) {
      // override. 需要覆盖head, 因为有效的PC至少
      // 等到下一个周期.
      validBuf(i) := false.B
    }
    // brTake in EXU should override fence from IDU
    val brTarget = MuxCase(
      // nextPC,
      lastPC, // fence.i
      Seq(
        brAbs -> brex.brVal,
        brRel -> (brex.brLPC + brex.brDel)
      )
    )
    pc := brTarget
    nextPC := brTarget + 4.U
  }.otherwise {
    when(iMem.ar.fire) {
      pc     := nextPC
      nextPC := nextPC + 4.U
    }
  }

  val ioid = io.out.bits
  ioid.pc   := Mux(io.out.valid, pcBuf(toidPtr), 0.U)
  ioid.inst := Mux(io.out.valid, instBuf(toidPtr), 0.U)

  when(iMem.ar.fire) {
    printf(cf"[  IF  ] Fetch PC = ${io.iMem.ar.bits.addr}%x\n")
  }
  when(iMem.r.fire) {
    printf(cf"[  IF  ] Recvd PC = ${pcBuf(tailPtr)}%x Val = ${io.iMem.r.bits.data}\n")
  }

  if (GlbCtrl.debug) {
    val pmu = Module(new FetchPMU)
    pmu.io.clock     := clock
    pmu.io.reset     := reset
    pmu.io.trigFetch := iMem.ar.fire
    pmu.io.trigRecvd := iMem.r.fire
    pmu.io.pcFetch   := pc
    pmu.io.pcRecvd   := pcBuf(tailPtr)
    pmu.io.inst      := io.out.bits.inst
  }
}
