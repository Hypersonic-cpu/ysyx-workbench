package rvproc

import chisel3._
import chisel3.util._

class FlushCtrl extends Module {
  val io = IO(new Bundle {
    // From EXU
    val brDet   = Flipped(Decoupled(Bool()))
    val brInfo  = Flipped(Decoupled(new ExecuteBackward))
    val exFired = Input(Bool())

    // From WBU
    val wbExcpFlush  = Input(Bool())
    val wbExcpTarget = Input(Tp.AddrType())

    // To IFU (registered pipeline)
    val toFetch       = Decoupled(new ExecuteBackward)
    val ifWbExcp      = Output(Bool())
    val ifWbExcpTarget = Output(Tp.AddrType())

    // To IDU (registered pipeline)
    val toIDU = Decoupled(Bool())

    // To EXU (registered self-flush)
    val exFlush = Output(Bool())

    // Combined flush
    val pipeFlush = Output(Bool())
    val excpFlush = Output(Bool())
  })

  // IFU redirect: registered pipeline (BusConnect semantics)
  io.brInfo.ready  := io.toFetch.ready
  io.toFetch.valid := RegEnable(
    io.brInfo.valid,
    false.B,
    io.toFetch.ready
  )
  io.toFetch.bits := RegEnable(
    io.brInfo.bits,
    io.toFetch.ready
  )

  // IDU flush: registered pipeline (BusConnect semantics)
  io.brDet.ready  := io.toIDU.ready
  io.toIDU.valid := RegEnable(
    io.brDet.valid,
    false.B,
    io.toIDU.ready
  )
  io.toIDU.bits := RegEnable(
    io.brDet.bits,
    false.B,
    io.toIDU.ready
  )

  // EXU self-flush: fires 1 cycle after mispredicting
  // instruction commits. Gated by exFired so the
  // mispredicting instruction itself is not squashed.
  io.exFlush := RegNext(
    io.brDet.bits && io.exFired,
    false.B
  )

  // Combined flush for Dispatcher / MUL / DIV
  val regBrFlush =
    RegNext(io.brDet.valid && io.brDet.bits, false.B)
  io.pipeFlush := regBrFlush || io.wbExcpFlush

  // Exception flush passthrough
  io.excpFlush      := io.wbExcpFlush
  io.ifWbExcp       := io.wbExcpFlush
  io.ifWbExcpTarget := io.wbExcpTarget
}
