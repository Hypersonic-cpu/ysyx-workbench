package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert
import rvproc.axi4._
import rvproc.axi4.AXI.RespStatus._
import rvproc.axi4.AXI.BurstOpts._
import rvproc.pmu.FetchPMU
import BitMath._

class FetchStage(resetVector: BigInt, PipeDepth: Int = 6)
    extends Module {
  val io = IO(new Bundle {
    val out    = Decoupled(new FetchToDecode)
    val fromEx = Flipped(Decoupled(new ExecuteBackward))
    val fromWb = Flipped(Decoupled(new InstCommit))
    val iMem   = new AXIBus
  })

  val idle :: serve :: Nil = Enum(2)

  val iMem = io.iMem

  // val pipeShift = io.out.ready && nextState =/= serve
  val brex      = io.fromEx.bits
  val brPending = RegInit(false.B)
  val flushWire = io.fromEx.valid && brex.take
  val instValid = iMem.r.valid
  // val flushThis = brPending || flushWire
  // brPending := (brPending || flushWire) && !instValid

  val pc     = RegInit(resetVector.U(ISA.RegBits.W))
  val nextPC = RegInit((resetVector + 4).U(ISA.RegBits.W))

  val vRing   = Reg(Vec(PipeDepth + 1, Bool()))
  val pcRing  = Reg(Vec(PipeDepth + 1, Tp.AddrType()))
  val headPtr = RegInit(0.U(log2Ceil(PipeDepth + 1).W))
  val tailPtr = RegInit(0.U(log2Ceil(PipeDepth + 1).W))
  def iotaMod(a: UInt) = Mux(a === PipeDepth.U, 0.U, a + 1.U)
  val bufFull = iotaMod(headPtr) === tailPtr

  when(iMem.r.fire) {
    vRing(tailPtr) := false.B
    tailPtr        := iotaMod(tailPtr)
  }
  when(iMem.ar.fire) {
    vRing(headPtr)  := true.B
    pcRing(headPtr) := pc
    headPtr         := iotaMod(headPtr)
  }
  assert(!bufFull, "IFU buffer should not full")

  // io.out.valid    := instValid && !flushThis && vRing(tailPtr)
  io.out.valid    := instValid && vRing(tailPtr) && !flushWire
  io.fromEx.ready := true.B
  io.fromWb.ready := true.B

  iMem.ar.bits.addr  := pc
  iMem.ar.bits.size  := 0x2.U                         // log2(4)
  iMem.ar.bits.len   := 0.U
  iMem.ar.bits.burst := INCR
  iMem.ar.bits.id    := 0.U
  iMem.ar.valid      := io.out.ready && !reset.asBool // TODO: 如果有空位才进行 // trigFetch
  iMem.r.ready       := io.out.ready                  // state === serve
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

  when(flushWire) {
    for (i <- 0 to PipeDepth) {
      // override. 需要覆盖head, 因为有效的PC至少
      // 等到下一个周期.
      vRing(i) := false.B
    }
    val brTarget = MuxCase(
      nextPC,
      Seq(
        brex.brAbs -> brex.brVal,
        brex.brRel -> (brex.brLPC + brex.brDel)
      )
    )
    // Use fire of REQ. Full 阻塞应该由缓存发起
    // when(iMem.ar.fire) {
      pc     := brTarget
      nextPC := brTarget + 4.U
    // }.otherwise {
    //   nextPC := brTarget
    // }
  }.otherwise {
    when(iMem.ar.fire) {
      pc     := nextPC
      nextPC := nextPC + 4.U
    }
  }

  val ioid = io.out.bits
  ioid.pc   := Mux(io.out.valid, pcRing(tailPtr), 0.U)
  ioid.inst := iMem.r.bits.data

  when(iMem.ar.fire) {
    printf(cf"[  IF  ] Fetch PC = ${io.iMem.ar.bits.addr}%x\n")
  }
  when(iMem.r.fire) {
    printf(cf"[  IF  ] Issue PC = ${pcRing(tailPtr)}%x\n")
  }

  val pmu         = Module(new FetchPMU)
  val delayedReq = (iMem.ar.fire)
  val delayedRsp = (iMem.r.fire)
  pmu.io.clock     := clock
  pmu.io.reset     := reset
  pmu.io.trigFetch := delayedReq
  pmu.io.trigIssue := delayedRsp
  pmu.io.pcFetch   := pc
  pmu.io.pcIssue   := pcRing(tailPtr)
  pmu.io.inst      := io.out.bits.inst
}
