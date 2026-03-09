package rvproc.device

import chisel3._
import chisel3.util._

import rvproc._
import rvproc.BitMath._
import rvproc.axi4._
import rvproc.axi4.AXI.RespStatus._

object CLINTAddr {
  val Base   = 0x0200_0000L
  val Size   = 0xc000L
  val Rsv    = 0xbfffL
  val OffClk = 0xbff8L
}

class CLINT extends Module {
  val io                  = IO(new Bundle {
    val port = Flipped(new AXIBus)
  })
  val idle :: proc :: Nil = Enum(2)

  val state = RegInit(idle)
  state := MuxLookup(state, idle)(
    Seq(
      idle -> Mux(io.port.ar.valid, proc, idle),
      proc -> Mux(io.port.r.ready, idle, proc)
    )
  )
  val retExt = RegInit(UInt((ISA.RegBits + 1).W), 0.U)

  io.port.aw.ready    := false.B
  io.port.w.ready     := false.B
  io.port.b.valid     := false.B
  io.port.b.bits.resp := SLVERR
  io.port.b.bits.id   := io.port.aw.bits.id
  assert(
    !reset.asBool || io.port.aw.valid,
    "Attemping to write CLINT timer"
  )
  io.port.ar.ready    := state === idle
  io.port.r.valid     := state === proc
  io.port.r.bits.data := retExt >> 1.U
  io.port.r.bits.resp := Mux(retExt(0), OKAY, SLVERR)
  io.port.r.bits.last := (state === proc) && io.port.r.ready
  io.port.r.bits.id   := io.port.ar.bits.id
  assert(io.port.ar.valid Implies (io.port.ar.bits.addr(1, 0) === 0.U))

  val mtime = RegInit(UInt(64.W), 0x0L.U)
  mtime := mtime + 1.U

  when(io.port.ar.valid) {
    val addr   = io.port.ar.bits.addr - CLINTAddr.Base.U
    val gotRet = MuxCase(
      0xbadc0de.U ## 0.U,
      Seq(
        (addr === CLINTAddr.OffClk.U)       -> mtime(31, 0) ## 1.U,
        (addr === (CLINTAddr.OffClk + 4).U) -> mtime(63, 32) ## 1.U
      )
    )
    // assert(addr(3, 0)===0xc.U, cf"CLINT DEBUG: mt${mtime}%x addr${addr}%x ret${gotRet >> 1.U}%x\n")
    retExt := gotRet
    assert(gotRet(0), cf"Unmapped CLINT addr ${addr}%x")
  }
}
