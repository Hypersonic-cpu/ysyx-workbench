package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert

import BitMath._
import rvproc.axi4.AXILite
import rvproc.MemLen._

// State:
// idle -(reqReady)-> macc -(respValid)-> hold
//

class MemoryStage extends Module {
  val io   = IO(new Bundle {
    val in   = Flipped(Decoupled(new ExecuteToMemory))
    val out  = Decoupled(new MemoryToWrBack)
    val dMem = new AXILite
  })
  val iowb = io.out.bits
  val ioex = io.in.bits
  val addr = ioex.aluOut
  val wrdt = ioex.rs2Val
  val dMem = io.dMem

  val idle :: serve :: hold :: Nil = Enum(3)

  val state     = RegInit(idle)
  val trigIss   = state === idle && io.in.valid && ioex.memOp.isEn
  val delayedSt = RegInit(false.B)
  when(io.in.valid) {
    delayedSt := ioex.memOp.isSt
  }
  val reqReady  = Mux(ioex.memOp.isSt, dMem.aw.ready, dMem.ar.ready)
  val respValid = Mux(~delayedSt, dMem.r.valid, dMem.b.valid)

  state := MuxLookup(state, idle)(
    Seq(
      // 没有LS操作的时候不需要等到内存空闲, 避免等待
      idle  -> MuxCase(
        idle,
        Seq(
          (io.in.valid && ioex.memOp.isEn)  ->
            Mux(trigIss && reqReady, serve, idle),
          (io.in.valid && ~ioex.memOp.isEn) -> hold
        )
      ),
      serve -> Mux(respValid, hold, serve),
      hold  -> Mux(io.out.ready, idle, hold)
    )
  )

  io.out.valid := state === hold
  // TODO: 内存没有就绪就让 Exu 等待是有问题的
  io.in.ready  := trigIss || ~ioex.memOp.isEn

  dMem.ar.bits.addr := addr & Tp.AddrAligner()
  dMem.aw.bits.addr := addr & Tp.AddrAligner()

  val shamt = addr(1, 0)
  val dmask = MuxLookup(ioex.memOp.len, 0.U)(
    Seq(
      MemLen.Byte -> 0xff.U,
      MemLen.Half -> 0xffff.U,
      MemLen.Word -> 0xffff_ffffL.U
    )
  )
  dMem.w.bits.data := (wrdt & dmask) << (shamt << 3.U)
  dMem.w.bits.strb := MuxLookup(ioex.memOp.len, 0.U)(
    Seq(
      MemLen.Byte -> 0x1.U,
      MemLen.Half -> 0x3.U,
      MemLen.Word -> 0xf.U
    )
  ) << shamt
  assert(
    (io.in.valid && ioex.memOp.len === Word)
      Implies (addr(1, 0) === 0.U),
    "Unaligned word access"
  )
  assert(
    (io.in.valid && ioex.memOp.len === Half)
      Implies (addr(0, 0) === 0.U),
    "Unaligned half access"
  )

  dMem.ar.valid := ~ioex.memOp.isSt && trigIss
  dMem.aw.valid := ioex.memOp.isSt && trigIss
  dMem.w.valid  := ioex.memOp.isSt && trigIss
  dMem.r.ready  := ~ioex.memOp.isSt && state === serve
  dMem.b.ready  := ioex.memOp.isSt && state === serve
  // TODO: 新建一个foward逻辑, 进行锁存
  val delayedLenOp = RegInit(MemLen.None)
  val delayedSext  = RegInit(false.B)
  val delayedShamt = RegInit(0.U(2.W))
  val aluReg       = Reg(Tp.RegType())
  val forwardReg   = Reg(new DecodeFoward)

  val sext      = ioex.memOp.sExt
  val lraw      = dMem.r.bits.data >> (delayedShamt << 3)
  val loadLatch = Reg(Tp.RegType())
  iowb.lsuOut := loadLatch
  when(respValid) {
    loadLatch := MuxLookup(delayedLenOp, 0.U)(
      Seq(
        MemLen.Byte -> Mux(delayedSext, lraw(7, 0).SExt(), lraw(7, 0)),
        MemLen.Half -> Mux(
          delayedSext,
          lraw(15, 0).SExt(),
          lraw(15, 0)
        ),
        MemLen.Word -> lraw
      )
    )
  }

  iowb.aluOut := aluReg
  iowb.foward := forwardReg

  when(io.in.valid) {
    forwardReg   := ioex.foward
    aluReg       := ioex.aluOut
    delayedSext  := sext
    delayedLenOp := ioex.memOp.len
    delayedShamt := shamt
  }

  when(trigIss) {
    printf(
      cf"[ ${ioex.foward.pc}%x LS ] Req W[${ioex.memOp.isSt}%d]"
        + cf" addr ${addr}%x, wrdt ${wrdt}%x, strb ${dMem.w.bits.strb}%x\n"
    )
  }.elsewhen(state === hold) {
    printf(
      cf"[ ${ioex.foward.pc}%x LS ] Resp ${dMem.r.bits.data}%x\n"
    )
  }
}
