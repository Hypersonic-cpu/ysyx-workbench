package rvproc

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxPath

/** +-----+ reqValid           ---> +-----+
  * |     | addr[log2(N)-1:0]  ---> |     |
  * |     | wrEn|memEn         ---> |     |
  * | CPU | wdata[31:0]        ---> | MEM |
  * |     | wmask[3:0]         ---> |     |
  * |     | <---          respValid |     |- reset
  * |     | <---        rdata[31:0] |     <- clock
  * +-----+                         +-----+
  */

class PMemBox extends BlackBox with HasBlackBoxPath {
  // Input: Master -> Slave
  //        Master <- Slave : Output
  val io = IO(new Bundle {
    val clock     = Input(Clock())
    val reset     = Input(Reset())
    val reqValid  = Input(Bool())
    val respReady = Input(Bool())
    val addr      = Input(Tp.AddrType())
    val wrEn      = Input(Bool())
    val wrData    = Input(Tp.RegType())
    val byteMask  = Input(UInt(8.W)) // UInt4
    val reqReady  = Output(Bool())
    val respValid = Output(Bool())
    val respData  = Output(Tp.RegType())
  })

  addPath(PATH.dpic("PMemBox.sv"))
}
