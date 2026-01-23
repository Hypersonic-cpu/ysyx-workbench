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

class StoreBuffer(Entries: Int) extends Module {
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
  io.in.b.bits.id   := io.in.aw.bits.id
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
  io.out.aw.bits.id    := 1.U
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
  val delayRetData    = RegNext(Mux1H(readHitReturn, buffer.map(_.data)))
  io.in.ar.ready    := readState === idle
  io.in.r.valid     := delayRetThisCyc || io.out.r.valid
  io.in.r.bits.resp := Mux(delayRetThisCyc, OKAY, io.out.r.bits.resp)
  io.in.r.bits.id   := io.in.ar.bits.id
  io.in.r.bits.last := true.B
  io.in.r.bits.data := Mux(
    delayRetThisCyc,
    delayRetData,
    io.out.r.bits.data
  )

  // val arAddr = RegEnable(io.in.ar.bits.addr, io.in.ar.valid)
  // val arSize = RegEnable(io.in.ar.bits.size, io.in.ar.valid)
  //io.in.ar.fire
  io.out.ar <> io.in.ar
  io.out.ar.valid   := readState === blocked && !readHitBlocked.orR
  io.out.r.ready    := io.in.r.ready
  // TODO: 应当暂时存储 input read addr

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

// class StoreeBuffer(
//   val entries:   Int = 4,
//   val addrWidth: Int = 32,
//   val dataWidth: Int = 64)
//     extends Module {
//   val io = IO(new Bundle {
//     // 来自 LSU 的接口
//     val lsu_st = Flipped(Decoupled(new Bundle {
//       val addr = UInt(addrWidth.W)
//       val data = UInt(dataWidth.W)
//       val strb = UInt((dataWidth / 8).W)
//     }))
//     val lsu_ld = new Bundle {
//       val addr = Input(UInt(addrWidth.W))
//       val hit  = Output(Bool())
//       val data = Output(UInt(dataWidth.W))
//     }
//     // AXI4 Master 接口 (写通道)
//     val axi    = new AxiLiteIO(addrWidth, dataWidth)
//   })
//
//   // 1. 定义 Buffer Entry
//   val regs = RegInit(
//     VecInit(Seq.fill(entries)(0.U.asTypeOf(new Entry)))
//   )
//
//   // 指针管理
//   val head = RegInit(
//     0.U(log2Ceil(entries).W)
//   ) // 指向最早的 entry (用于 commit)
//   val tail  = RegInit(0.U(log2Ceil(entries).W)) // 指向下一个空闲位置 (用于 alloc)
//   val count = RegInit(0.U(log2Ceil(entries + 1).W))
//
//   val full  = count === entries.U
//   val empty = count === 0.U
//
//   // 2. LSU Store 写入 (Allocation)
//   io.lsu_st.ready := !full
//   when(io.lsu_st.fire) {
//     regs(tail).addr  := io.lsu_st.bits.addr
//     regs(tail).data  := io.lsu_st.bits.data
//     regs(tail).strb  := io.lsu_st.bits.strb
//     regs(tail).valid := true.B
//     tail             := Mux(tail === (entries - 1).U, 0.U, tail + 1.U)
//     count            := count + 1.U
//   }
//
//   // 3. LSU Load 查找 (Read-Through)
//   // 采用 CAM 逻辑：从 tail 向 head 逆序查找最新的匹配项
//   val hits = VecInit(
//     regs.map(r => r.valid && r.addr === io.lsu_ld.addr)
//   ).asUInt
//   io.lsu_ld.hit  := hits.orR
//   // 简化的选择逻辑：实际中应选择最新的(最靠近tail的)那一个
//   io.lsu_ld.data := Mux1H(hits, regs.map(_.data))
//
//   // 4. 写回状态机 (AXI4 Commit)
//   val s_idle :: s_aw :: s_w :: s_b :: Nil = Enum(4)
//   val state                               = RegInit(s_idle)
//
//   io.axi.aw.valid     := (state === s_aw)
//   io.axi.aw.bits.addr := regs(head).addr
//   io.axi.w.valid      := (state === s_w)
//   io.axi.w.bits.data  := regs(head).data
//   io.axi.w.bits.strb  := regs(head).strb
//   io.axi.b.ready      := (state === s_b)
//
//   switch(state) {
//     is(s_idle) {
//       if (!empty) state := s_aw
//     }
//     is(s_aw) {
//       if (io.axi.aw.fire) state := s_w
//     }
//     is(s_w) {
//       if (io.axi.w.fire) state := s_b
//     }
//     is(s_b) {
//       if (io.axi.b.fire) {
//         regs(head).valid := false.B
//         head             := Mux(head === (entries - 1).U, 0.U, head + 1.U)
//         count            := count - 1.U
//         state            := s_idle
//       }
//     }
//   }
// }
