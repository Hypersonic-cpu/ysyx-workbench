package rvproc.device

import chisel3._
import chisel3.util._

import rvproc._
import rvproc.axi4._
import rvproc.axi4.AXI.RespStatus._

// AXI slave that always returns SLVERR or DECERR.
class FaultBox(slverr: Boolean) extends Module {
  val io = IO(new Bundle {
    val port = Flipped(new AXIBus)
  })

  val resp =
    if (slverr) SLVERR else DECERR

  // Read channel — handles bursts
  val rIdle :: rServe :: Nil = Enum(2)
  val rState                 = RegInit(rIdle)
  val rLen                   = Reg(UInt(8.W))
  val rCnt                   = Reg(UInt(8.W))
  val rId                    = Reg(UInt(4.W))

  io.port.ar.ready    := rState === rIdle
  io.port.r.valid     := rState === rServe
  io.port.r.bits.data := 0.U
  io.port.r.bits.resp := resp
  io.port.r.bits.last := rCnt === rLen
  io.port.r.bits.id   := rId

  switch(rState) {
    is(rIdle) {
      when(io.port.ar.fire) {
        rState := rServe
        rLen   := io.port.ar.bits.len
        rCnt   := 0.U
        rId    := io.port.ar.bits.id
      }
    }
    is(rServe) {
      when(io.port.r.fire) {
        when(rCnt === rLen) {
          rState := rIdle
        }.otherwise {
          rCnt := rCnt + 1.U
        }
      }
    }
  }

  // Write channel — single beat
  val wIdle :: wResp :: Nil = Enum(2)
  val wState                = RegInit(wIdle)
  val wId                   = Reg(UInt(4.W))

  io.port.aw.ready    := wState === wIdle
  io.port.w.ready     := wState === wIdle
  io.port.b.valid     := wState === wResp
  io.port.b.bits.resp := resp
  io.port.b.bits.id   := wId

  switch(wState) {
    is(wIdle) {
      when(
        io.port.aw.fire && io.port.w.fire
      ) {
        wId    := io.port.aw.bits.id
        wState := wResp
      }
    }
    is(wResp) {
      when(io.port.b.fire) {
        wState := wIdle
      }
    }
  }
}
