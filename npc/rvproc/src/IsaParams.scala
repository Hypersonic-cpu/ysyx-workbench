package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert

object PATH {
  val dpicPath = "/home/kong/ysyx-workbench/npc/rvproc/dpic/"
  val sramPath = "/home/kong/ysyx-workbench/npc/libs/sram/"
  def dpic(s: String) =
    java.nio.file.Paths.get(dpicPath, s).toString()
  def sram(s: String) =
    java.nio.file.Paths.get(sramPath, s).toString()
}

object GlbCtrl {
  var debug   = true
  var sta     = false
  var sramlib = false
}

object ISA {
  val InstBits   = 32
  val RegBits    = 32
  val RegNum     = 16
  val RegIdxBits = 4
  val CsrIdxBits = 12
  val AddrBits   = 32 // Also bus bits
  val WordShift  = 2
}

object Tp {
  def RegType()     = UInt(ISA.RegBits.W)
  def InstType()    = UInt(ISA.InstBits.W)
  def RegIdxType()  = UInt(ISA.RegIdxBits.W)
  def CsrIdxType()  = UInt(ISA.CsrIdxBits.W)
  // Now it equals RegType() so no padding is needed.
  def AddrType()    = UInt(ISA.AddrBits.W)
  def AddrAligner() = ~((ISA.AddrBits / 8 - 1).U(ISA.AddrBits.W))
  def TimeType()    = UInt(64.W)
}

object ITYPE extends ChiselEnum {
  val tR, tI, tS, tB, tU, tJ, tN = Value
}

object AnsiColor {
  implicit class ColorString(val s: String) extends AnyVal {
    def red:    String = s"${Console.RED}$s${Console.RESET}"
    def green:  String = s"${Console.GREEN}$s${Console.RESET}"
    def blue:   String = s"${Console.BLUE}$s${Console.RESET}"
    def yellow: String = s"${Console.YELLOW}$s${Console.RESET}"
  }
}
