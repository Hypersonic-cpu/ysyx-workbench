package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert
// import chisel3.util.experimental.loadMemoryFromFileInline
// import firrtl.annotations.MemoryLoadFileType


object BitMath {
  implicit class UIntSignExtender(val i: UInt) extends AnyVal {
    def SExt(width: Int = ISA.RegBits): UInt = {
      i.asSInt.pad(width).asUInt
    }
    def MSBU(idx: Int = 0): UInt = {
      val chosen = ISA.RegBits-1-idx
      i(chosen, chosen)
    }
    def MSB(idx: Int = 0) = {
      val chosen = ISA.RegBits-1-idx
      i(chosen, chosen)
    }
    def UExt(width: Int = ISA.RegBits+1): UInt = {
      i.pad(width)
    }
  }
}
import BitMath._

class rvCore() extends Module {
  val io = IO(new Bundle{
  })

  val ifs = Module(new FetchStage)
  val ids = Module(new DecodeStage)
  val exs = Module(new ExecuteStage)
  val lss = Module(new MemoryStage)
  val wbs = Module(new WrBackStage)
  val reg = Module(new RegFile)

  BusConnect(ifs.io.out, ids.io.in)
  BusConnect(ids.io.out, exs.io.in)
  BusConnect(exs.io.out, lss.io.in)
  BusConnect(lss.io.out, wbs.io.in)
  BusConnect(wbs.io.out, ifs.io.in)
  // always_comb
  BusConnect(ids.io.toReg, reg.io.fromId, BusType.SingleCyc)
  BusConnect(reg.io.toId, ids.io.fromReg, BusType.SingleCyc)
  BusConnect(wbs.io.toReg, reg.io.fromWb, BusType.SingleCyc)
  
  dontTouch(ifs.io)
  dontTouch(ids.io)
  dontTouch(exs.io)
  dontTouch(lss.io)
  dontTouch(wbs.io)
  dontTouch(reg.io)
}

class rvCoreWrapper() extends Module {
  val io = IO(new Bundle{ })
  val core = Module(new rvCore())
  dontTouch(core.io)
}

