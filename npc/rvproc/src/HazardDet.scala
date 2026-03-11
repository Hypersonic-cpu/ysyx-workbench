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

class RegDstBundle extends FwBundle {
  // val valid = Bool()
  val gprWE = Bool()
  // val gprFw = Bool() // forward avaiable
  val gprRd = Tp.RegIdxType()
  // val gprDt = Tp.RegType()
  val csrWE = Bool()
}

class DecodeHazard extends Bundle {
  val rs1  = Tp.RegIdxType()
  val rs2  = Tp.RegIdxType()
  val use1 = Bool()
  val use2 = Bool()
  val useC = Bool()
}

class SourceForward extends Bundle {
  val block = Bool()
  val rs1fw = Bool()
  val rs1dt = Tp.RegType()
  val rs2fw = Bool()
  val rs2dt = Tp.RegType()
}

class RAWForward extends Module {
  val io = IO(new Bundle {
    val valid  = Input(Bool())
    val reqid  = Input(Tp.RegIdxType())
    val exsrd  = Input(new RegDstBundle)
    val skidrd = Input(new RegDstBundle)
    val lssrd  = Input(new RegDstBundle)
    val wbsrd  = Input(new RegDstBundle)
    val reqdt  = Output(Tp.RegType())
    val reqfw  = Output(Bool())
    val reqbl  = Output(Bool())
  })

  def conflictWith(
    valid: Bool,
    self:  UInt,
    other: RegDstBundle
  ) = {
    other.gprWE && valid && self.orR && other.gprRd === self && other.valid
  }

  val stages     = Seq(io.exsrd, io.skidrd, io.lssrd, io.wbsrd)
  val rawArr     = Wire(Vec(4, Bool()))
  rawArr(0) := conflictWith(io.valid, io.reqid, io.exsrd)
  rawArr(1) := conflictWith(io.valid, io.reqid, io.skidrd)
  rawArr(2) := conflictWith(io.valid, io.reqid, io.lssrd)
  rawArr(3) := conflictWith(io.valid, io.reqid, io.wbsrd)
  // [0] EX, [1] SKID, [2] LS, [3] WB
  val fwdArr     = VecInit(stages.map(_.gprFw))
  val fwdSrc     = VecInit(stages.map(_.gprDt))
  val rawBlocked = rawArr.asUInt
  val rawStall   = rawArr.asUInt & ~fwdArr.asUInt
  val fwdIndex   = PriorityEncoder(rawBlocked)
  io.reqbl := rawStall.orR
  io.reqfw := rawBlocked.orR && !rawStall.orR
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
    val srcfw  = Output(new SourceForward)
    val decode = Input(new DecodeHazard)
    val exsrd  = Input(new RegDstBundle)
    val skidrd = Input(new RegDstBundle)
    val lssrd  = Input(new RegDstBundle)
    val wbsrd  = Input(new RegDstBundle)
  })

  val rs1ctl = Module(new RAWForward)
  val rs2ctl = Module(new RAWForward)

  rs1ctl.io.valid  := io.decode.use1
  rs1ctl.io.reqid  := io.decode.rs1
  rs1ctl.io.exsrd  := io.exsrd
  rs1ctl.io.skidrd := io.skidrd
  rs1ctl.io.lssrd  := io.lssrd
  rs1ctl.io.wbsrd  := io.wbsrd

  rs2ctl.io.valid  := io.decode.use2
  rs2ctl.io.reqid  := io.decode.rs2
  rs2ctl.io.exsrd  := io.exsrd
  rs2ctl.io.skidrd := io.skidrd
  rs2ctl.io.lssrd  := io.lssrd
  rs2ctl.io.wbsrd  := io.wbsrd

  val csrraw = VecInit(
    Seq(io.exsrd, io.skidrd, io.lssrd, io.wbsrd).map(r =>
      r.csrWE && io.decode.useC && r.valid
    )
  ).asUInt.orR

  io.srcfw.block := rs1ctl.io.reqbl || rs2ctl.io.reqbl || csrraw
  io.srcfw.rs1fw := rs1ctl.io.reqfw
  io.srcfw.rs1dt := rs1ctl.io.reqdt
  io.srcfw.rs2fw := rs2ctl.io.reqfw
  io.srcfw.rs2dt := rs2ctl.io.reqdt
}
