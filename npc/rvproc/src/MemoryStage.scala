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

// State:
// idle -(reqReady)-> macc -(respValid)-> hold
//

class MemoryStage extends Module {
  val io   = IO(new Bundle {
    val in   = Flipped(Decoupled(new ExecuteToMemory))
    val out  = Decoupled(new MemoryToWrBack)
    val dMem = new AXIBus
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
  val reqReady  = Mux(!ioex.memOp.isSt, dMem.aw.ready, dMem.ar.ready)
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
  // TODO: Remove this. (any better methods?)
  // assert(
  //   trigIss Implies (
  //     ((addr >= 0x3000_0000L.U) && (addr <= 0x3fff_ffffL.U))        // FLASH
  //       || ((addr >= 0x0f00_0000L.U) && (addr <= 0x0f00_1fffL.U))   // SRAM
  //       || ((addr >= 0x1000_0000L.U) && (addr <= 0x1000_0fffL.U))   // SPI
  //       || ((addr >= 0x0200_0000L.U) && (addr <= 0x0200_ffffL.U))   // CLINT
  //       || ((addr >= 0x1000_2000L.U) && (addr <= 0x1000_200fL.U))   // GPIO
  //       || ((addr >= 0x1001_1000L.U) && (addr <= 0x1001_1007L.U))   // PS/2
  //       || ((addr >= 0x8000_0000L.U)) // PSRAM and CHIPLINK
  //   ),
  // cf"Address ${addr}%x out of bound!"
  // )
  // TODO: Fix difftest back

  dMem.ar.valid := ~ioex.memOp.isSt && trigIss
  dMem.aw.valid := ioex.memOp.isSt && trigIss
  dMem.w.valid  := ioex.memOp.isSt && trigIss
  dMem.r.ready  := ~ioex.memOp.isSt && io.out.ready //  && state === serve
  dMem.b.ready  := ioex.memOp.isSt && io.out.ready  //  && state === serve

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
  if (GlbCtrl.debug) {
    iowb.foward.stallT := Mux(
      io.in.valid && !io.out.valid,
      StallCause.LoadStore,
      io.in.bits.foward.stallT
    )
  } else {
    iowb.foward.stallT := DontCare
  }

  when(io.out.valid) {
    printf(
      cf"WR?${ioex.memOp.isSt} Addr ${ioex.aluOut}%x LoadData ${iowb.lsuOut}%x WrData ${wrdt}%x\n"
    )
  }

  val pmu = Module(new LoadStorePMU)
  pmu.io.clock    := clock
  pmu.io.reset    := reset
  pmu.io.trigReq  := state === idle && trigIss && reqReady
  pmu.io.trigResp := state === serve && respValid
  pmu.io.addr     := addr
}
