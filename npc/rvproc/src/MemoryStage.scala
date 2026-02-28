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

class MemoryStage extends Module {
  val io   = IO(new Bundle {
    val in     = Flipped(Decoupled(new ExecuteToMemory))
    val out    = Decoupled(new MemoryToWrBack)
    val dMem   = new AXIBus
    val fwdDet = Output(new FwBundle)
  })
  val iowb = io.out.bits
  val ioex = io.in.bits
  val addr = ioex.aluOut
  val wrdt = ioex.rs2Val
  val dMem = io.dMem

  // val idle :: serve :: hold :: Nil = Enum(3)
  val idle :: serve :: Nil = Enum(2)

  val state     = RegInit(idle)
  val trigIss   = state === idle && io.in.valid && ioex.memOp.isEn
  val reqReady  = Mux(!ioex.memOp.isSt, dMem.ar.ready, dMem.aw.ready)
  val respValid = Mux(!ioex.memOp.isSt, dMem.r.valid, dMem.b.valid)

  // FIXME: awValid 和 bValid 同时高的时候 (1周期延迟), 会有问题吗?
  state := MuxLookup(state, idle)(
    Seq(
      idle  -> Mux(trigIss && reqReady, serve, idle),
      serve -> Mux(respValid, idle, serve)
    )
  )

  // WARN: WBU如果需要等待, 则这里会出问题(respValid仅有1cyc高)
  // val delay1Trig = RegNext(trigIss)
  io.out.valid := io.in.valid && (!ioex.memOp.isEn || respValid)
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

  when(ioex.memOp.isSt && trigIss && addr === 0.U) {
    printf(cf"[BUG] Store to addr 0! pc=${ioex.foward.pc}%x inst=${ioex.foward.inst}%x data=${wrdt}%x aluOut=${ioex.aluOut}%x\n")
  }
  assert(
    !(ioex.memOp.isSt && trigIss && addr === 0.U),
    cf"LSU store to addr 0: pc=${ioex.foward.pc}%x inst=${ioex.foward.inst}%x data=${wrdt}%x"
  )
  dMem.r.ready  := ~ioex.memOp.isSt && io.out.ready
  dMem.b.ready  := ioex.memOp.isSt && io.out.ready

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
  iowb.foward <> io.in.bits.foward

  /** Forward */
  io.fwdDet.valid := io.in.valid
  io.fwdDet.gprFw := ioex.foward.wbSel === WbSel.fromAlu // !ioex.memOp.isEn
  io.fwdDet.gprDt := ioex.aluOut
  // io.fwdDet.gprFw := io.out.valid &&
  //   io.in.bits.foward.wbSel =/= WbSel.fromCsr
  // io.fwdDet.gprDt := Mux(
  //   ioex.memOp.isEn,
  //   /* fromMem */ iowb.lsuOut,
  //   /* fromAlu|PC */ ioex.aluOut
  // )

  if (GlbCtrl.debug) {
    iowb.foward.stallT := Mux(
      io.in.valid && !io.out.valid,
      StallCause.LoadStore,
      io.in.bits.foward.stallT
    )
  } else {
    iowb.foward.stallT := DontCare
  }

  when(io.out.fire && ioex.memOp.isEn) {
    printf(
      cf"Rsp < WR?${ioex.memOp.isSt} Addr ${ioex.aluOut}%x LoadData ${iowb.lsuOut}%x\n"
    )
  }
  when(io.in.fire && ioex.memOp.isEn) {
    printf(
      cf"Req > WR?${ioex.memOp.isSt} Addr ${ioex.aluOut}%x wrData ${wrdt}%x\n"
    )
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
