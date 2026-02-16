package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert

import BitMath._
import rvproc.axi4._
import rvproc.MemLen._
import rvproc.axi4.AXI.BurstOpts._
import rvproc.axi4.AXI.RespStatus._
import rvproc.GlbCtrl.debug

class StoreBuffer(Entries: Int, Id: Int = 1) extends Module {
  val io = IO(new Bundle {
    val cpuSide = Flipped(new AXIBus)
    val memSide = new AXIBus
    val empty   = Output(Bool())
  })

  /** NOTE: Write buffer */
  class Entry extends Bundle {
    val addr   = Tp.AddrType()
    val data   = Tp.RegType()
    val strb   = UInt((ISA.RegBits / 8).W)
    val size   = AXI.SizeType()
    val valid  = Bool()
    val issued = Bool()
  }
  val buffer = Reg(Vec(Entries, new Entry))
  // Earliest entry
  val head    = RegInit(0.U(log2Ceil(Entries).W))
  // Next available position
  val tail    = RegInit(0.U(log2Ceil(Entries).W))
  val count   = RegInit(0.U(log2Ceil(Entries + 1).W))
  val wbfull  = count === Entries.U
  val wbempty = count === 0.U
  io.empty := wbempty

  val isskip = !wbempty && !buffer(head).valid && !buffer(head).issued
  count := count + io.cpuSide.aw.fire.asUInt - io.memSide.b.fire.asUInt - isskip.asUInt

  def iotaMod(a: UInt) = Mux(a === (Entries - 1).U, 0.U, a + 1.U)

  val writeHitEnt = VecInit(
    buffer.map(r =>
      r.valid
        && r.addr === io.cpuSide.aw.bits.addr
        && r.strb === io.cpuSide.w.bits.strb
    )
  )

  io.cpuSide.aw.ready    := !wbfull
  io.cpuSide.w.ready     := !wbfull
  io.cpuSide.b.valid     := RegNext(io.cpuSide.aw.fire)
  io.cpuSide.b.bits.id   := Id.U
  io.cpuSide.b.bits.resp := OKAY

  when(io.memSide.b.fire) {
    buffer(head).valid  := false.B
    buffer(head).issued := false.B
    head                := iotaMod(head)
  }.elsewhen(isskip) {
    head := iotaMod(head)
  }

  // Overwrite deque logic (when head === tail)
  when(io.cpuSide.aw.fire) {
    // 这个 valid 不由状态机维持, 可能不符合AXI规定
    for (i <- 0 until Entries) {
      when(writeHitEnt(i)) {
        buffer(i).valid := false.B
      }
    }
    assert(io.cpuSide.w.valid, "AW and W Channel not coherent");
    assert(io.cpuSide.b.ready, "B channel not ready by host")
    buffer(tail).addr := io.cpuSide.aw.bits.addr
    buffer(tail).size  := io.cpuSide.aw.bits.size
    buffer(tail).data  := io.cpuSide.w.bits.data
    buffer(tail).strb  := io.cpuSide.w.bits.strb
    buffer(tail).valid := true.B
    tail               := iotaMod(tail)
  }

  // Overwrite deque logic on `issue`
  when(io.memSide.aw.fire) {
    buffer(head).issued := true.B
  }

  io.memSide.w.bits.last   := true.B
  io.memSide.w.valid       := buffer(head).valid
  io.memSide.w.bits.strb   := buffer(head).strb
  io.memSide.w.bits.data   := buffer(head).data
  io.memSide.aw.valid      := buffer(head).valid
  io.memSide.aw.bits.addr  := buffer(head).addr
  io.memSide.aw.bits.size  := buffer(head).size
  io.memSide.aw.bits.burst := INCR
  io.memSide.aw.bits.id    := Id.U
  io.memSide.aw.bits.len   := 0.U
  io.memSide.b.ready       := true.B

  /** NOTE: Read handling */
  val idle :: blocked :: busy :: Nil = Enum(3)

  val readHitEnt     = VecInit(
    buffer.map(r => r.valid && r.addr === io.cpuSide.ar.bits.addr)
  ).asUInt
  // Hit but not write the entire word. Block until this write is done.
  val readHitBlocked =
    VecInit(buffer.map(r => !r.strb.andR)).asUInt & readHitEnt
  val readHitReturn  = readHitEnt & (~readHitBlocked)
  val readRetThisCyc = io.cpuSide.ar.fire && readHitReturn.orR
  val readState      = RegInit(idle)

  if (debug) {
    dontTouch(readHitEnt)
    dontTouch(readHitReturn)
    dontTouch(readHitBlocked)
    dontTouch(readRetThisCyc)
  }

  readState := MuxLookup(readState, idle)(
    Seq(
      idle    ->
        Mux(
          io.cpuSide.ar.fire,
          Mux(
            readHitReturn.orR,
            idle,
            blocked
            // Mux(readHitBlocked.orR, blocked, busy)
          ),
          idle
        ),
      blocked -> Mux(io.memSide.ar.fire, busy, blocked),
      busy    -> Mux(io.memSide.r.fire, idle, busy)
    )
  )

  val delayRetThisCyc = RegNext(readRetThisCyc)
  val delayReadBlock  = RegNext(readHitBlocked)
  val delayRetData    = RegNext(Mux1H(readHitReturn, buffer.map(_.data)))
  val arAddr          = RegEnable(io.cpuSide.ar.bits.addr, io.cpuSide.ar.fire)
  val arSize          = RegEnable(io.cpuSide.ar.bits.size, io.cpuSide.ar.fire)
  val rResp           = RegEnable(io.memSide.r.bits.resp, io.memSide.r.fire)
  val rData           = RegEnable(io.memSide.r.bits.data, io.memSide.r.fire)
  val rValid          = RegInit(false.B)
  rValid := Mux(rValid, !io.cpuSide.r.fire, io.memSide.r.fire)

  io.cpuSide.ar.ready      := readState === idle
  io.cpuSide.r.valid       := delayRetThisCyc || rValid
  io.cpuSide.r.bits.resp   := Mux(delayRetThisCyc, OKAY, rResp)
  io.cpuSide.r.bits.id     := Id.U
  io.cpuSide.r.bits.last   := true.B
  io.cpuSide.r.bits.data   := Mux(
    delayRetThisCyc,
    delayRetData,
    rData
  )
  // io.memSide.ar <> io.cpuSide.ar
  io.memSide.ar.bits.addr  := arAddr
  io.memSide.ar.bits.size  := arSize
  io.memSide.ar.bits.len   := 0.U
  io.memSide.ar.bits.burst := INCR
  io.memSide.ar.bits.id    := Id.U

  io.memSide.ar.valid := readState === blocked && !delayReadBlock.orR && wbempty
  io.memSide.r.ready  := readState === busy                           // RegNext(io.cpuSide.r.ready) // WARN: 出现多余的一拍 ready ?

  assert(
    io.cpuSide.ar.valid Implies io.cpuSide.r.ready,
    cf"Host R channel not ready"
  )

  // printf("StQue")
  // for (i <- 0 until Entries) {
  //   printf(cf" [$i] v${buffer(i).valid} ${buffer(i).addr}%x:${buffer(i).data}%x")
  // }
  // printf("\n")
}
