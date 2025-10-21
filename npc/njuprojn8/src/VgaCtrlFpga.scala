package NjuProjN8

import chisel3._
import chisel3.util._
import chisel3.experimental._
import chisel3.util.experimental.loadMemoryFromFileInline
import firrtl.annotations.MemoryLoadFileType

class ImageROM(
  imageWidth: Int,
  imageHeight: Int,
  dataWidth: Int,
  imageBinFilePath: String) extends Module {
  val io = IO(new Bundle {
    val addr = Input(UInt(log2Ceil(imageWidth * imageHeight).W))
    val data = Output(UInt(dataWidth.W))
  })

  val depth = imageWidth * imageHeight
  val rom = SyncReadMem(depth, UInt(dataWidth.W))
  io.data := rom.read(io.addr)

  loadMemoryFromFileInline(
    rom, imageBinFilePath, MemoryLoadFileType.Hex)
}

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

  val imgRom = Module(new ImageROM(640, 480, 32,
    "/mnt/hgfs/Arch-PA/ysyx-workbench/npc/njuprojn8/img-bin/JiaoTongUniversity.hex"))
  imgRom.io.addr := Mux(vgaCtrl.io.oValid, xPos * 480.U + yPos, 0.U)

  vgaCtrl.io.rawData := imgRom.io.data(23, 0)

  io.syncHor := vgaCtrl.io.syncHor
  io.syncVer := vgaCtrl.io.syncVer
  io.blankN  := vgaCtrl.io.oValid

  for (i <- 0 until 3) {
    io.vgaRGB(i) := vgaCtrl.io.oRGB(i)
  }
}
