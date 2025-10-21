package NjuProjN8

import chisel3._
import chisel3.util._

object Res640x480 {
  val horFrontporch   = 96 .U(10.W)
  val horActive       = 144.U(10.W)
  val horBackporch    = 784.U(10.W)
  val horTotal        = 800.U(10.W)

  val verFrontporch   = 2  .U(10.W)
  val verActive       = 35 .U(10.W)
  val verBackporch    = 515.U(10.W)
  val verTotal        = 525.U(10.W)

}

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
  
  val currHor = RegInit(1.U(10.W))
  val currVer = RegInit(1.U(10.W))
  currHor := Mux(
    currHor === Res640x480.horTotal,
    1.U, currHor + 1.U
  )
  currVer := Mux(
    currVer === Res640x480.verTotal,
    1.U, 
    currVer + 
      Mux(currHor === Res640x480.horTotal, 1.U, 0.U)
  )

  io.syncHor := (currHor > Res640x480.horFrontporch)
  io.syncVer := (currVer > Res640x480.verFrontporch)

  val validHor = 
    (currHor > Res640x480.horActive) & 
    (currHor <= Res640x480.horBackporch)

  val validVer = 
    (currVer > Res640x480.verActive) & 
    (currVer <= Res640x480.verBackporch)

  val valid = validHor & validVer
  io.oValid := valid
  io.posHor := Mux(valid, 
    currHor - Res640x480.horActive - 1.U, 0.U)
  io.posVer := Mux(valid, 
    currVer - Res640x480.verActive - 1.U, 0.U)
  for (i <- 0 until 3) {
    io.oRGB(i) := io.rawData(i*8+7, i*8)
  }
}
