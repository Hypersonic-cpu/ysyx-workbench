package NjuProjN8

import chisel3._
import chisel3.util._
import chisel3.experimental._

class VgaCtrlFpga extends Module {
  val io = IO(new Bundle {
    val syncHor = Output(Bool())
    val syncVer = Output(Bool())
    val blankN  = Output(Bool())
    val vgaRGB  = Output(Vec(3, UInt(8.W)))
  })

  val vgaCtrl = Module(new VgaCtrl())
  // maybe_unused
  val xPos = vgaCtrl.io.posHor
  val yPos = vgaCtrl.io.posVer
  // TODO: should be F(xpos, ypos)
  vgaCtrl.io.rawData := 0xff0000.U

  io.syncHor := vgaCtrl.io.syncHor
  io.syncVer := vgaCtrl.io.syncVer
  io.blankN  := vgaCtrl.io.oValid

  for (i <- 0 until 3) {
    io.vgaRGB(i) := vgaCtrl.io.oRGB(i)
  }
}
