package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert

object BitMath {
  implicit class UIntSignExtender(val i: UInt) extends AnyVal {
    def SExt(width: Int = ISA.RegBits):     UInt = {
      i.asSInt.pad(width).asUInt
    }
    def MSBU(idx: Int = 0):                 UInt = {
      val chosen = ISA.RegBits - 1 - idx
      i(chosen, chosen)
    }
    def MSB(idx: Int = 0):                  UInt = {
      val chosen = ISA.RegBits - 1 - idx
      i(chosen, chosen)
    }
    def UExt(width: Int = ISA.RegBits + 1): UInt = {
      i.pad(width)
    }
  }

  implicit class LogicPropagator(val p: Bool) extends AnyVal {
    def Implies(q:  Bool): Bool = (~p) || q
    def Excludes(q: Bool): Bool = p Implies (~q)
  }

  implicit class UIntAddrMask(val i: UInt) extends AnyVal {
    def LoPass(n: Int):    UInt = {
      val mask = ((1 << n) - 1).U
      i & mask(n - 1, 0)
    }
    def AlignedTo(n: Int): UInt = {
      (i >> n.U) << n.U
    }
  }
}
