package rvProc

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxResource
import chisel3.util.experimental.loadMemoryFromFileInline

class EcallBox extends BlackBox with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val isEcall  = Input(Bool())
    val isEbreak = Input(Bool())
    val pcin  = Input(Tp.PCType())
    val a10in = Input(Tp.RegType())
  })
  
  addResource("/mnt/hgfs/Arch-PA/ysyx-workbench/npc/rvproc/dpic/EcallBox.sv")
}
