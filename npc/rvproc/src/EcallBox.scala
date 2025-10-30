package rvProc

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxPath

class EcallBox extends BlackBox with HasBlackBoxPath {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val isEcall  = Input(Bool())
    val isEbreak = Input(Bool())
    val pcin  = Input(Tp.PCType())
    val a10in = Input(Tp.RegType())
  })
  
  addPath("/mnt/hgfs/Arch-PA/ysyx-workbench/npc/rvproc/dpic/EcallBox.sv")
}
