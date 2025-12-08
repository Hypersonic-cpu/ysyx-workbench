package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert

import BitMath._

class LSU extends Module {
  val io = IO(new Bundle {
    val valid  = Input(Bool())
    val addr   = Input(Tp.AddrType())
    val data   = Input(Tp.RegType())
    val memOp = Input(new MemOp)
    val load   = Output(Tp.RegType())
  })

  val dMem = Module(new PMemBox())
  val lenOp = io.memOp.len
  dMem.io.clock := clock
  dMem.io.reset := reset
  dMem.io.addr  := io.addr
  dMem.io.data  := MuxLookup(lenOp, 0.U) (Seq(
    MemLen.Byte -> (io.data(7, 0) << (io.addr(1,0) << 3.U)),
    MemLen.Half -> (io.data(15,0) << (io.addr(1,1) << 4.U)),
    MemLen.Word -> io.data
  ))
  dMem.io.byteMask := MuxLookup(lenOp, 0.U) (
    Seq(
      MemLen.Byte -> (0x1.U << io.addr(1, 0)),
      MemLen.Half -> (0x3.U << (io.addr(1, 1) << 1.U)),
      MemLen.Word -> 0xf.U
    )
  )
  dMem.io.memEn := io.valid && lenOp =/= MemLen.None
  // Load and store should not happen together
  dMem.io.wrEn  := io.memOp.isSt


  val delayedLenOp = RegInit(MemLen.None)
  val delayedSext = RegInit(false.B)
  val delayedAddr = Reg(Tp.AddrType())
  val sext = io.memOp.sExt
  delayedSext := sext
  delayedLenOp := lenOp
  delayedAddr := io.addr
  val lraw = dMem.io.loadRaw >> (delayedAddr(1, 0) << 3)
  // printf(cf"DPI Chisel Raw ${lraw}%x SEXT ${sext}\n")
  io.load := MuxLookup(delayedLenOp, 0.U) (Seq(
    MemLen.Byte -> Mux(delayedSext, lraw(7, 0).SExt(), lraw(7, 0)),
    MemLen.Half -> Mux(delayedSext, lraw(15, 0).SExt(), lraw(15, 0)),
    MemLen.Word -> lraw
    )
  )

  when (io.valid && lenOp =/= MemLen.None) {
    printf(cf"[          LS ] dMemPort Req W[${io.memOp.isSt}%d] addr ${io.addr}%x, byteMask ${dMem.io.byteMask}%x \n")
  }
}

class MemoryStage extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new ExecuteToMemory))
    val out = Decoupled(new MemoryToWrBack)
  })
  /**
    * Cycle  1   2   3   1
    * State  I   H   I   I
    * Time       Req Resp
    */
  val idle :: hold :: Nil = Enum(2)
  val state = RegInit(idle)
  state := MuxLookup(state, idle) (Seq(
    hold -> Mux(io.out.ready, idle, hold),
    idle -> Mux(io.in.valid , hold, idle)
  ))

  io.out.valid := state === hold
  io.in.ready := state === idle

  val iLsu = Module(new LSU)
  val ioex = io.in.bits
  val iowb = io.out.bits
  iLsu.io.valid := io.in.valid
  iLsu.io.addr := ioex.aluOut
  iLsu.io.data := ioex.rs2Val
  iLsu.io.memOp := ioex.memOp
  iowb.lsuOut  := iLsu.io.load

  // NOTE: Fowards are delayed
  val aluReg = Reg(Tp.RegType())
  iowb.aluOut  := aluReg
  // TODO: 新建一个foward逻辑, 进行锁存
  val forwardReg = Reg(new DecodeFoward)
  iowb.foward  := forwardReg

  when (io.in.valid) {
    forwardReg := ioex.foward
    aluReg := ioex.aluOut
  }

  when (io.in.valid) {
    printf(cf"[ ${ioex.foward.pc}%x LS ] Requesting ${iLsu.io.addr}%x\n")
  }.elsewhen(state === hold) {
    printf(cf"[ ${ioex.foward.pc}%x LS ] Response   ${iLsu.io.load}%x\n")
  }
}
