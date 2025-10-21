package NjuProjN8

import chisel3._
import chisel3.util._

class VgaCtrl extends Module {
  val io = IO(new Bundle{
    val rawData   = Input(UInt(24.W))
    val posHor    = Output(UInt(10.W))
    val posVer    = Output(UInt(10.W))
    val syncHor   = Output(Bool())
    val syncVer   = Output(Bool())
    val oValid    = Output(Bool())
    val oRGB      = Output(Vec(3, UInt(8.W)))
  })
}
