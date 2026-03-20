package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert

import BitMath._
import rvproc.axi4._
import rvproc.MemLen._
import rvproc.axi4.AXI.BurstOpts._
import rvproc.axi4.AXI.RespStatus._
import rvproc.pmu.LoadStorePMU
import rvproc.GlbCtrl.debug
import rvproc.AnsiColor.ColorString

class MemoryStage extends Module {
  val io   = IO(new Bundle {
    val in        = Flipped(Decoupled(new ExecuteToMemory))
    val out       = Decoupled(new MemoryToWrBack)
    val dMem      = new AXIBus
    val fwdDet    = Output(new FwBundle)
    val excpFlush = Input(Bool())
  })
  val iowb = io.out.bits
  val ioex = io.in.bits
  val addr = ioex.aluOut
  val wrdt = ioex.rs2Val
  val dMem = io.dMem

  // Misalignment detection (moved from EXU for timing)
  val wordMis      =
    addr(1, 0) =/= 0.U &&
      ioex.memOp.len === MemLen.Word
  val halfMis      =
    addr(0) =/= 0.U &&
      ioex.memOp.len === MemLen.Half
  val excpMisalign =
    io.in.valid && ioex.isMemEn &&
      (wordMis || halfMis)

  // val idle :: serve :: hold :: Nil = Enum(3)
  val idle :: serve :: Nil = Enum(2)

  val state     = RegInit(idle)
  val trigIss   =
    state === idle && io.in.valid && ioex.isMemEn &&
      !io.excpFlush && !excpMisalign
  val reqReady  = Mux(!ioex.memOp.isSt, dMem.ar.ready, dMem.aw.ready)
  val respValid = Mux(!ioex.memOp.isSt, dMem.r.valid, dMem.b.valid)

  state := MuxLookup(state, idle)(
    Seq(
      idle  -> Mux(trigIss && reqReady, serve, idle),
      serve -> Mux(respValid, idle, serve)
    )
  )

  // WARN: WBU如果需要等待, 则这里会出问题(respValid仅有1cyc高)
  // val delay1Trig = RegNext(trigIss)
  io.out.valid := io.in.valid &&
    (!ioex.isMemEn || excpMisalign || respValid) &&
    !io.excpFlush
  io.in.ready  := io.out.ready && ((!trigIss && state === idle) || io.out.valid)

  dMem.ar.bits.addr  := addr // & Tp.AddrAligner()
  dMem.aw.bits.addr  := addr // & Tp.AddrAligner()
  dMem.ar.bits.burst := INCR
  dMem.aw.bits.burst := INCR
  dMem.ar.bits.size  := ioex.memOp.len.asUInt
  dMem.aw.bits.size  := ioex.memOp.len.asUInt
  dMem.ar.bits.id    := 1.U
  dMem.aw.bits.id    := 1.U
  dMem.ar.bits.len   := 0.U  // NOTE: len 是传输的次数. 大小是 size
  dMem.aw.bits.len   := 0.U
  dMem.w.bits.last   := true.B

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
  // Misalignment is now handled as exception above

  dMem.ar.valid := ~ioex.memOp.isSt && trigIss
  dMem.aw.valid := ioex.memOp.isSt && trigIss
  dMem.w.valid  := ioex.memOp.isSt && trigIss

  dMem.r.ready := ~ioex.memOp.isSt && io.out.ready
  dMem.b.ready := ioex.memOp.isSt && io.out.ready

  val sext      = ioex.memOp.sExt
  val loadValue = dMem.r.bits.data >> (shamt << 3)

  // TODO: memory test
  iowb.lsuOut :=
    MuxLookup(ioex.memOp.len, 0.U)(
      Seq(
        MemLen.Byte -> Mux(
          sext,
          loadValue(7, 0).SExt(),
          loadValue(7, 0)
        ),
        MemLen.Half -> Mux(
          sext,
          loadValue(15, 0).SExt(),
          loadValue(15, 0)
        ),
        MemLen.Word -> loadValue
      )
    )

  iowb.aluOut <> io.in.bits.aluOut
  iowb.forward <> io.in.bits.forward

  /** Exception handling */
  // LSU exception detection (causes 5, 7, 13, 15)
  val lsuExcp      = Wire(Bool())
  val lsuExcpCause = Wire(UInt(4.W))
  when(ioex.isMemEn && respValid && !ioex.memOp.isSt) {
    lsuExcp      := dMem.r.bits.resp =/= OKAY
    lsuExcpCause :=
      Mux(dMem.r.bits.resp === SLVERR, 5.U, 13.U)
    when(dMem.r.bits.resp =/= OKAY) {
      printf(cf"WARN: LSU Exception: ${dMem.r.bits.resp}\n")
    }
  }.elsewhen(
    ioex.isMemEn && respValid && ioex.memOp.isSt
  ) {
    lsuExcp      := dMem.b.bits.resp =/= OKAY
    lsuExcpCause :=
      Mux(dMem.b.bits.resp === SLVERR, 7.U, 15.U)
  }.otherwise {
    lsuExcp      := false.B
    lsuExcpCause := 0.U
  }

  when(lsuExcp) {
    iowb.forward.excpValid := true.B
    iowb.forward.excpFlush := true.B
    iowb.forward.mcause    := lsuExcpCause
    iowb.forward.gprWE     := false.B
    iowb.forward.csrWE     := false.B
  }

  // Misalignment exception (causes 4, 6)
  val isLoad =
    ioex.isMemEn && !ioex.memOp.isSt
  when(excpMisalign) {
    iowb.forward.excpValid := true.B
    iowb.forward.excpFlush := true.B
    iowb.forward.mcause    :=
      Mux(isLoad, 4.U, 6.U)
    iowb.forward.gprWE     := false.B
    iowb.forward.csrWE     := false.B
  }

  /** Forward */
  io.fwdDet.valid := io.in.valid
  io.fwdDet.gprFw := ioex.forward.wbSel === WbSel.fromAlu
  io.fwdDet.gprDt := ioex.aluOut

  if (GlbCtrl.debug) {
    iowb.forward.stallT := Mux(
      io.in.valid && !io.out.valid,
      StallCause.LoadStore,
      io.in.bits.forward.stallT
    )
  } else {
    iowb.forward.stallT := DontCare
  }

  if (GlbCtrl.debug) {
    val pmu = Module(new LoadStorePMU)
    pmu.io.clock    := clock
    pmu.io.reset    := reset
    pmu.io.trigReq  := state === idle && trigIss && reqReady
    pmu.io.trigResp := state === serve && respValid
    pmu.io.addr     := addr
  }
}
