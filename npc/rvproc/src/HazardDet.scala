package rvproc

import chisel3._
import chisel3.util._
import chisel3.assert.Assert
import rvproc.axi4._
import rvproc.axi4.AXI.RespStatus._
import rvproc.axi4.AXI.BurstOpts._
import rvproc.pmu.FetchPMU
import BitMath._

class HazardDet extends Module {
  val io = IO(new Bundle {})
}

class FwBundle extends Bundle {
  val valid = Bool()
  val gprFw = Bool() // forward avaiable
  val gprDt = Tp.RegType()
}

class RdBundle extends FwBundle {
  // val valid = Bool()
  val gprWE = Bool()
  // val gprFw = Bool() // forward avaiable
  val gprRd = Tp.RegIdxType()
  // val gprDt = Tp.RegType()
  val csrWE = Bool()
  val csrRd = Tp.CsrIdxType()
}

class DecodeHazard extends Bundle {
  val rs1  = Tp.RegIdxType()
  val rs2  = Tp.RegIdxType()
  val csr  = Tp.CsrIdxType()
  val use1 = Bool()
  val use2 = Bool()
  val useC = Bool()
}

class SourceFoward extends Bundle {
  val block = Bool()
  val rs1fw = Bool()
  val rs1dt = Tp.RegType()
  val rs2fw = Bool()
  val rs2dt = Tp.RegType()
}

class RAWForward extends Module {
  val io = IO(new Bundle {
    val valid = Input(Bool())
    val reqid = Input(Tp.RegIdxType())
    val exsrd = Input(new RdBundle)
    val lssrd = Input(new RdBundle)
    val wbsrd = Input(new RdBundle)
    val reqdt = Output(Tp.RegType())
    val reqfw = Output(Bool())
    val reqbl = Output(Bool())
  })

  def conflictWith(
    valid: Bool,
    self:  UInt,
    other: RdBundle
  ) = {
    other.gprWE && valid && self.orR && other.gprRd === self && other.valid
  }

  val rawArr = Wire(Vec(3, Bool()))
  rawArr(0) := conflictWith(io.valid, io.reqid, io.exsrd)
  rawArr(1) := conflictWith(io.valid, io.reqid, io.lssrd)
  rawArr(2) := conflictWith(io.valid, io.reqid, io.wbsrd)
  // [0] EX, [1] LS, [2] WB
  val fwdArr     = VecInit(Seq(io.exsrd, io.lssrd, io.wbsrd).map(_.gprFw))
  val fwdSrc     = VecInit(Seq(io.exsrd, io.lssrd, io.wbsrd).map(_.gprDt))
  val rawBlocked = rawArr.asUInt               // conflict, not stall
  // val rawForward = rawArr.asUInt & fwdArr.asUInt
  val rawStall   = rawArr.asUInt & ~fwdArr.asUInt
  val fwdIndex   = PriorityEncoder(rawBlocked) // rawForward)
  io.reqbl := rawStall.orR                    // rawBlocked.orR && !(rawForward.orR)
  io.reqfw := rawBlocked.orR && !rawStall.orR // block but not stall
  io.reqdt := fwdSrc(fwdIndex)

  if (GlbCtrl.debug) {
    val fwdArrDbg = fwdArr.asUInt
    dontTouch(fwdArrDbg)
    dontTouch(fwdArr)
    dontTouch(rawArr)
    dontTouch(fwdIndex)
    dontTouch(fwdSrc)
    dontTouch(io)
    dontTouch(rawBlocked)
    dontTouch(rawStall)
  }
}

class RAWDet extends Module {
  val io = IO(new Bundle {
    val srcfw  = Output(new SourceFoward)
    val decode = Input(new DecodeHazard)
    val exsrd  = Input(new RdBundle)
    val lssrd  = Input(new RdBundle)
    val wbsrd  = Input(new RdBundle)
  })

  val rs1ctl = Module(new RAWForward)
  val rs2ctl = Module(new RAWForward)

  rs1ctl.io.valid := io.decode.use1
  rs1ctl.io.reqid := io.decode.rs1
  rs1ctl.io.exsrd := io.exsrd
  rs1ctl.io.lssrd := io.lssrd
  rs1ctl.io.wbsrd := io.wbsrd

  rs2ctl.io.valid := io.decode.use2
  rs2ctl.io.reqid := io.decode.rs2
  rs2ctl.io.exsrd := io.exsrd
  rs2ctl.io.lssrd := io.lssrd
  rs2ctl.io.wbsrd := io.wbsrd

  def conflictCsr(valid: Bool, self: UInt, other: RdBundle) = {
    other.csrWE && valid && other.csrRd === self && other.valid
  }

  val csrraw = VecInit(
    Seq(io.exsrd, io.lssrd, io.wbsrd).map(r =>
      conflictCsr(io.decode.useC, io.decode.csr, r)
    )
  ).asUInt.orR

  io.srcfw.block := rs1ctl.io.reqbl || rs2ctl.io.reqbl || csrraw
  io.srcfw.rs1fw := rs1ctl.io.reqfw
  io.srcfw.rs1dt := rs1ctl.io.reqdt
  io.srcfw.rs2fw := rs2ctl.io.reqfw
  io.srcfw.rs2dt := rs2ctl.io.reqdt
}
