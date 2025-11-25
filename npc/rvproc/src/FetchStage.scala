package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert
// import chisel3.util.experimental.loadMemoryFromFileInline
// import firrtl.annotations.MemoryLoadFileType

class FetchStage extends Module {
  val io = IO(new Bundle{
    val in  = Flipped(Decoupled(new WrBackToFetch))
    val out = Decoupled(new FetchToDecode)
  })

  // wait for NEXT stage
  val idle :: hold :: Nil = Enum(2)
  val state = RegInit(idle)
  state := MuxLookup(state, idle) (Seq(
    // Transferred and reset
    hold -> Mux(io.out.ready, idle, hold),
    idle -> Mux(io.in.valid , hold, idle)
  ))
  io.in.ready  := true.B
  io.out.valid := true.B

  // TODO: Add state
  val pc = RegInit(0x80000000L.U(ISA.RegBits.W))

  val iMem = Module(new PMemBox)
  iMem.io.clock := clock
  iMem.io.reset := reset

  iMem.io.addr  := pc
  iMem.io.memEn := true.B
  iMem.io.wrEn  := false.B
  iMem.io.byteMask := 0.U
  iMem.io.data  := 0.U

  val iowb = io.in.bits
  val ioid = io.out.bits
  pc := iowb.npc

  ioid.pc   := pc
  ioid.inst := iMem.io.loadRaw

  printf(cf"[ ${pc}%x IF ] inst ${ioid.inst}%x\n")
}
