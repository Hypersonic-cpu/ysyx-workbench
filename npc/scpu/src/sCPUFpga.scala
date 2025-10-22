package sCPU

import chisel3._
import chisel3.util._
import chisel3.experimental._
import chisel3.util.experimental.loadMemoryFromFileInline
import firrtl.annotations.MemoryLoadFileType

// class ImageROM(
//   imageWidth: Int,
//   imageHeight: Int,
//   dataWidth: Int,
//   imageBinFilePath: String) extends Module {
//   val io = IO(new Bundle {
//     val addr = Input(UInt(log2Ceil(imageWidth * imageHeight).W))
//     val data = Output(UInt(dataWidth.W))
//   })
//
//   val depth = imageWidth * imageHeight
//   val rom = SyncReadMem(depth, UInt(dataWidth.W))
//   io.data := rom.read(io.addr)
//
//   loadMemoryFromFileInline(
//     rom, imageBinFilePath, MemoryLoadFileType.Hex)
// }

class sCPUFpga extends Module {
  val io = IO(new Bundle {
    val syncHor = Output(Bool())
    val syncVer = Output(Bool())
    val blankN  = Output(Bool())
    val vgaRGB  = Output(Vec(3, UInt(8.W)))
  })

}
