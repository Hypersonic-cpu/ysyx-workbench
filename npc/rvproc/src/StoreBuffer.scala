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
    val in    = Flipped(new AXIBus)
    val out   = new AXIBus
    val empty = Output(Bool())
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
  count := count + io.in.aw.fire.asUInt - io.out.b.fire.asUInt - isskip.asUInt

  def iotaMod(a: UInt) = Mux(a === (Entries - 1).U, 0.U, a + 1.U)

  val writeHitEnt = VecInit(
    buffer.map(r =>
      r.valid
        && r.addr === io.in.aw.bits.addr
        && r.strb === io.in.w.bits.strb
    )
  )

  io.in.aw.ready    := !wbfull
  io.in.w.ready     := !wbfull
  io.in.b.valid     := RegNext(io.in.aw.fire)
  io.in.b.bits.id   := Id.U
  io.in.b.bits.resp := OKAY

  when(io.out.b.fire) {
    buffer(head).valid  := false.B
    buffer(head).issued := false.B
    head                := iotaMod(head)
  }.elsewhen(isskip) {
    head := iotaMod(head)
  }

  // Overwrite deque logic (when head === tail)
  when(io.in.aw.fire) {
    // 这个 valid 不由状态机维持, 可能不符合AXI规定
    for (i <- 0 until Entries) {
      when(writeHitEnt(i)) {
        buffer(i).valid := false.B
      }
    }
    assert(io.in.w.valid, "AW and W Channel not coherent");
    assert(io.in.b.ready, "B channel not ready by host")
    buffer(tail).addr := io.in.aw.bits.addr
    buffer(tail).size  := io.in.aw.bits.size
    buffer(tail).data  := io.in.w.bits.data
    buffer(tail).strb  := io.in.w.bits.strb
    buffer(tail).valid := true.B
    tail               := iotaMod(tail)
  }

  // Overwrite deque logic on `issue`
  when(io.out.aw.fire) {
    buffer(head).issued := true.B
  }

  io.out.w.bits.last   := true.B
  io.out.w.valid       := buffer(head).valid
  io.out.w.bits.strb   := buffer(head).strb
  io.out.w.bits.data   := buffer(head).data
  io.out.aw.valid      := buffer(head).valid
  io.out.aw.bits.addr  := buffer(head).addr
  io.out.aw.bits.size  := buffer(head).size
  io.out.aw.bits.burst := INCR
  io.out.aw.bits.id    := Id.U
  io.out.aw.bits.len   := 0.U
  io.out.b.ready       := true.B

  /** NOTE: Read handling */
  val idle :: blocked :: busy :: Nil = Enum(3)

  val readHitEnt     = VecInit(
    buffer.map(r => r.valid && r.addr === io.in.ar.bits.addr)
  ).asUInt
  // Hit but not write the entire word. Block until this write is done.
  val readHitBlocked =
    VecInit(buffer.map(r => !r.strb.andR)).asUInt & readHitEnt
  val readHitReturn  = readHitEnt & (~readHitBlocked)
  val readRetThisCyc = io.in.ar.fire && readHitReturn.orR
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
          io.in.ar.fire,
          Mux(
            readHitReturn.orR,
            idle,
            blocked
            // Mux(readHitBlocked.orR, blocked, busy)
          ),
          idle
        ),
      blocked -> Mux(io.out.ar.fire, busy, blocked),
      busy    -> Mux(io.out.r.fire, idle, busy)
    )
  )

  val delayRetThisCyc = RegNext(readRetThisCyc)
  val delayReadBlock  = RegNext(readHitBlocked)
  val delayRetData    = RegNext(Mux1H(readHitReturn, buffer.map(_.data)))
  val arAddr = RegEnable(io.in.ar.bits.addr, io.in.ar.fire)
  val arSize = RegEnable(io.in.ar.bits.size, io.in.ar.fire)
  val rResp = RegEnable(io.out.r.bits.resp, io.out.r.fire)
  val rData = RegEnable(io.out.r.bits.data, io.out.r.fire)
  val rValid = RegInit(false.B)
  rValid := Mux(rValid, !io.in.r.fire, io.out.r.fire)

  io.in.ar.ready    := readState === idle
  io.in.r.valid     := delayRetThisCyc || rValid
  io.in.r.bits.resp := Mux(delayRetThisCyc, OKAY, rResp)
  io.in.r.bits.id   := Id.U
  io.in.r.bits.last := true.B
  io.in.r.bits.data := Mux(
    delayRetThisCyc,
    delayRetData,
    rData
  )
  // io.out.ar <> io.in.ar
  io.out.ar.bits.addr  := arAddr
  io.out.ar.bits.size  := arSize
  io.out.ar.bits.len   := 0.U
  io.out.ar.bits.burst := INCR
  io.out.ar.bits.id    := Id.U

  io.out.ar.valid := readState === blocked && !delayReadBlock.orR // !readHitBlocked.orR
  io.out.r.ready  := readState === busy // RegNext(io.in.r.ready) // WARN: 出现多余的一拍 ready ? 

  assert(
    io.in.ar.valid Implies io.in.r.ready,
    cf"Host R channel not ready"
  )

  // printf("StQue")
  // for (i <- 0 until Entries) {
  //   printf(cf" [$i] v${buffer(i).valid} ${buffer(i).addr}%x:${buffer(i).data}%x")
  // }
  // printf("\n")
}
