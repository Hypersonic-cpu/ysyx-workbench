package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert

import BitMath._

// State:
// idle -(reqReady)-> macc -(respValid)-> hold
//

class MemoryStage extends Module {
  val io   = IO(new Bundle {
    val in  = Flipped(Decoupled(new ExecuteToMemory))
    val out = Decoupled(new MemoryToWrBack)
  })
  val iowb = io.out.bits
  val ioex = io.in.bits
  val addr = ioex.aluOut
  val wrdt = ioex.rs2Val
  val dMem = Module(new PMemBox())

  val idle :: serve :: hold :: Nil = Enum(3)

  val state = RegInit(idle)
  state := MuxLookup(state, idle)(
    Seq(
      idle  -> Mux(io.in.valid && dMem.io.reqReady, serve, idle),
      serve -> Mux(
        // TODO:: 没有LS操作的时候不需要等到内存空闲.
        ioex.memOp.isEn,
        Mux(dMem.io.respValid, hold, serve),
        hold
      ),
      hold  -> Mux(io.out.ready, idle, hold)
    )
  )

  io.out.valid := state === hold
  io.in.ready  := state === idle && dMem.io.reqReady

  // NOTE: dMem Port related
  dMem.io.clock     := clock
  dMem.io.reset     := reset
  dMem.io.addr      := addr
  dMem.io.wrData    := MuxLookup(ioex.memOp.len, 0.U)(
    Seq(
      MemLen.Byte -> (wrdt(7, 0) << (addr(1, 0) << 3.U)),
      MemLen.Half -> (wrdt(15, 0) << (addr(1, 1) << 4.U)),
      MemLen.Word -> wrdt(31, 0)
    )
  )
  dMem.io.byteMask  := MuxLookup(ioex.memOp.len, 0.U)(
    Seq(
      MemLen.Byte -> (0x1.U << addr(1, 0)),
      MemLen.Half -> (0x3.U << (addr(1, 1) << 1.U)),
      MemLen.Word -> 0xf.U
    )
  )
  // TODO: 目前设计有点奇怪, 输入不由LSU锁存, 但是mem resp 的数据在
  // WBU没有准备好的时候需要由LSU锁存. 但三MemPort本身也有锁存能力.
  dMem.io.reqValid  := state === idle && io.in.valid && ioex.memOp.isEn
  dMem.io.wrEn      := ioex.memOp.isSt
  dMem.io.respReady := state === serve
  // TODO: 新建一个foward逻辑, 进行锁存
  val delayedLenOp = RegInit(MemLen.None)
  val delayedSext  = RegInit(false.B)
  val delayedAddr  = Reg(Tp.AddrType())
  val aluReg       = Reg(Tp.RegType())
  val forwardReg   = Reg(new DecodeFoward)

  val sext      = ioex.memOp.sExt
  val lraw      = dMem.io.respData >> (delayedAddr(1, 0) << 3)
  val loadLatch = Reg(Tp.RegType())
  iowb.lsuOut := loadLatch
  when(dMem.io.respValid) {
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
    delayedAddr  := addr
  }

  when(io.in.valid && ioex.memOp.isEn) {
    printf(
      cf"[ ${ioex.foward.pc}%x LS ] Req W[${ioex.memOp.isSt}%d]"
        + cf" addr ${addr}%x, byteMask ${dMem.io.byteMask}%x \n"
    )
  }.elsewhen(state === hold) {
    printf(
      cf"[ ${ioex.foward.pc}%x LS ] Response   ${dMem.io.respData}%x\n"
    )
  }
}
/*                  +---------------+
 * lsu_reqValid     |               |
 *               ---+               +------------------------
 *                          +-------+
 * lsu_reqReady             |       |
 *               -----------+       +------------------------
 *               --\ /-------------\ /-----------------------
 * lsu_addr         X  0x80400000   X
 *               --/ \-------------/ \-----------------------
 *               --\                 /-----------------------
 * lsu_wen          X               X
 *               --/ --------------- \-----------------------
 *               --------------------------------------------
 * lsu_wdata
 *               --------------------------------------------
 *               --------------------------------------------
 * lsu_wmask
 *               --------------------------------------------
 *                                       +---------------+
 * lsu_respValid                         |               |
 *               ------------------------+               +---
 *                                               +-------+
 * lsu_respReady                                 |       |
 *               --------------------------------+       +---
 *               -----------------------\ /-------------\ /--
 * lsu_rdata                             X  0x12345678   X
 *               -----------------------/ \-------------/ \--
 */
