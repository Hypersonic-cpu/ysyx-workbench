package rvproc.device

import chisel3._
import chisel3.util._
import rvproc.GlbCtrl
import rvproc.PATH

/** 1RW SRAM BlackBox — matches sram_1rw.sv interface. */
class SRAM1RW(wordSize: Int, numWords: Int)
    extends BlackBox(
      Map(
        "WORD_SIZE" -> wordSize,
        "NUM_WORDS" -> numWords
      )
    ) with HasBlackBoxPath {
  val io = IO(new Bundle {
    val clk0 = Input(Clock())
    val csb0 = Input(Bool())
    val web0 = Input(Bool())
    val addr0 =
      Input(UInt(log2Ceil(numWords).W))
    val din0  = Input(UInt(wordSize.W))
    val dout0 = Output(UInt(wordSize.W))
  })
  addPath(PATH.sram("sram_1rw.sv"))
}

/** Unified cache array: SyncReadMem (sramlib=false)
  * or SRAM BlackBox (sramlib=true). Single 1RW port.
  */
class CacheArray(depth: Int, width: Int)
    extends Module {
  val io = IO(new Bundle {
    val raddr = Input(UInt(log2Ceil(depth).W))
    val ren   = Input(Bool())
    val rdata = Output(UInt(width.W))
    val waddr = Input(UInt(log2Ceil(depth).W))
    val wen   = Input(Bool())
    val wdata = Input(UInt(width.W))
  })

  if (GlbCtrl.sramlib) {
    val sram = Module(new SRAM1RW(width, depth))
    sram.io.clk0  := clock
    sram.io.csb0  := !(io.ren || io.wen)
    sram.io.web0  := !io.wen
    sram.io.addr0 := Mux(io.wen, io.waddr, io.raddr)
    sram.io.din0  := io.wdata
    io.rdata      := sram.io.dout0
  } else {
    val mem =
      SyncReadMem(depth, UInt(width.W))
    io.rdata := mem.read(io.raddr, io.ren)
    when(io.wen) { mem.write(io.waddr, io.wdata) }
  }
}
