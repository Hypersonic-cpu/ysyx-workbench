package rvproc.cache

import chisel3._
import chisel3.util._
import rvproc.Tp

class NextLinePrefetcher(conf: iCacheConf)
    extends Module {
  val io = IO(new Bundle {
    val snoopDone = Input(Bool())
    val snoopAddr = Input(Tp.AddrType())
    val pending   = Output(Bool())
    val addr      = Output(Tp.AddrType())
    val consumed  = Input(Bool())
    val flush     = Input(Bool())
  })

  def blkOf(x: UInt) =
    x(conf.addrBits - 1, conf.offBits) ##
      0.U(conf.offBits.W)

  val pfPending = RegInit(false.B)
  val pfAddr    = Reg(Tp.AddrType())

  io.pending := pfPending
  io.addr    := pfAddr

  when(io.consumed || io.flush) {
    pfPending := false.B
  }
  when(io.snoopDone) {
    pfAddr    := blkOf(io.snoopAddr) +
      conf.lineBytes.U
    pfPending := true.B
  }
}
