package NjuProjN7

import chisel3._
import chisel3.util._
import chisel3.experimental._

class Ps2DetectorFpga extends Module {
  val io = IO(new Bundle {
    val ps2Clk = Input(Bool())
    val ps2Dat = Input(Bool())
    val bufOverflow = Output(Bool())
    val keyPressed = Output(Bool())
    val segDisplay = Output(Vec(8, UInt(8.W)))
    val ready = Output(Bool())
    val ps2Code = Output(UInt(8.W))
    // val outDt = Output(UInt(8.W))
    // val oOvfl = Output(Bool())
  })

  val pressState = RegInit(false.B)
  val keycodeState = RegInit(0.U(8.W))
  io.keyPressed := pressState

  val acqOut = WireInit(false.B)
  val lastEn = RegInit(false.B)
  val currOut = Reg(Vec(2, UInt(8.W)))
  currOut := VecInit(0xF0.U, 0xF0.U)
  // val currOut = Reg(VecInit())

  val det = Module(new Ps2Detector())
  io.bufOverflow := det.io.oOvfl
  det.io.ps2Clk := io.ps2Clk
  det.io.ps2Dat := io.ps2Dat
  det.io.acqOut := acqOut
  io.ready := det.io.outEn
  io.ps2Code := det.io.outDt

  /** cycles 
    * [0] output ready
    * [1] stored ready into register, acqOut = hi
    * [2] got output
    */
  lastEn := det.io.outEn 
  acqOut := lastEn
  currOut(0) := Mux(acqOut, currOut(0), det.io.outDt)
  currOut(1) := Mux(acqOut, currOut(1), currOut(0)  )

  val segDecode = for {
    i <- 0 until 8
  } yield (Module(new HexTo7Seg()))

  for (i <- 2 until 8) {
    segDecode(i).io.in := 0.U
    segDecode(i).io.ena := false.B
  }
  segDecode(1).io.in := keycodeState(7, 4)
  segDecode(0).io.in := keycodeState(3, 0)
  segDecode(1).io.ena := pressState
  segDecode(0).io.ena := pressState
  
  // printf(cf"$pressState%d, Hi = ${currOut(1)}%x, Lo = ${currOut(0)}%x\n")
  switch (pressState) {
    is (true.B) {
      val released = (currOut(1) === 0xF0.U) //  & (currOut(0) === keycodeState)
      pressState := ~released
    }
    is (false.B) {
      val pressed = (currOut(1) =/= 0xF0.U)
      pressState := pressed
      keycodeState := currOut(0)
    }
  }

  for (i <- 0 until 8) {
    io.segDisplay(i) := ~segDecode(i).io.segMsbA
  }
}
