package rvproc.device

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxPath

import rvproc._
import rvproc.axi4._
import rvproc.BitMath._

class UART extends Module {
  val io      = IO(new Bundle {
    val port = Flipped(new AXILite)
  })
  val uartbox = Module(new UARTBox)
  uartbox.io.clock := clock
  uartbox.io.reset := reset

  io.port.ar.ready    := false.B
  io.port.r.valid     := false.B
  io.port.r.bits.resp := AXIRespStatus.SLVERR
  io.port.r.bits.data := 0xbadc0de.U
  assert(~io.port.ar.valid, "AR valid to UART: UnImpl")

  io.port.aw.ready    := true.B
  io.port.w.ready     := true.B
  io.port.b.valid     := true.B
  io.port.b.bits.resp := AXIRespStatus.OKAY

  val lastAw = RegInit(false.B)
  lastAw := io.port.aw.valid
  val activate = io.port.aw.valid && !lastAw
  uartbox.io.bytein := io.port.w.bits.data(7, 0)
  assert(
    io.port.w.valid Implies (io.port.w.bits.strb === 1.U),
    "Serial write: bytemask not 1"
  )
  uartbox.io.active := activate
}

class UARTBox extends BlackBox with HasBlackBoxPath {
  val io = IO(new Bundle {
    val clock  = Input(Clock())
    val reset  = Input(Reset())
    val bytein = Input(UInt(8.W))
    val active = Input(Bool())
  })

  addPath(PATH.dpic("UARTBox.sv"))
}
