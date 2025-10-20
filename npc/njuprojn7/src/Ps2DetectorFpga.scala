package NjuProjN7

import chisel3._
import chisel3.util._
import chisel3.experimental._

class Ps2DetectorFpga extends Module {
  val io = IO(new Bundle {
    val ps2Clk = Input(Bool())
    val ps2Dat = Input(Bool())
    val bufOverflow = Output(Bool())
    val keyPressed  = Output(Bool())
    val ps2Code     = Output(UInt(8.W))
    val segDisplay  = Output(Vec(8, UInt(8.W)))
    // val outDt = Output(UInt(8.W))
    // val oOvfl = Output(Bool())
  })

  val pressState = RegInit(false.B)
  val keycodeState = RegInit(0.U(8.W))
  io.keyPressed := pressState

  val acqOut = WireInit(false.B)
  // val lastEn = RegInit(false.B)
  val currOut = for { i <- 0 until 4 } yield RegInit(0xF0.U(8.W))
  // val currOut = Reg(VecInit())

  val det = Module(new Ps2Detector())
  io.bufOverflow := det.io.oOvfl
  det.io.ps2Clk := io.ps2Clk
  det.io.ps2Dat := io.ps2Dat
  det.io.acqOut := acqOut
  // io.ready := det.io.outEn
  io.ps2Code := det.io.outDt

  acqOut := det.io.outEn
  currOut(0) := Mux(~acqOut, currOut(0), det.io.outDt)
  currOut(1) := Mux(~acqOut, currOut(1), currOut(0)  )
  currOut(2) := Mux(~acqOut, currOut(2), currOut(1)  )
  currOut(3) := Mux(~acqOut, currOut(3), currOut(2)  )

  val ascii = Module(new KeyToASCII())
  ascii.io.keycode := keycodeState

  // when (det.io.outEn) {
  //   printf(cf"Out enable: ${det.io.outDt}%x\n")
  // }
  // printf(cf">>> ${currOut(3)}%x ${currOut(2)}%x ${currOut(1)}%x ${currOut(0)}%x\n")

  val segDecode = for {
    i <- 0 until 8
  } yield (Module(new HexTo7Seg()))

  val pressCount = RegInit(0.U(16.W))
  for (i <- 4 until 8) {
    segDecode(i).io.in := pressCount(4*(i-4)+3, 4*(i-4)+0)
    segDecode(i).io.ena := true.B
  }
  segDecode(1).io.in := keycodeState(7, 4)
  segDecode(0).io.in := keycodeState(3, 0)
  segDecode(1).io.ena := pressState
  segDecode(0).io.ena := pressState

  segDecode(3).io.in := ascii.io.ascii(7, 4)
  segDecode(2).io.in := ascii.io.ascii(3, 0)
  segDecode(3).io.ena := pressState
  segDecode(2).io.ena := pressState
  
  // printf(cf"$pressState%d, Hi = ${currOut(1)}%x, Lo = ${currOut(0)}%x\n")
  switch (pressState) {
    is (true.B) {
      val released = (currOut(1) === 0xF0.U) //  & (currOut(0) === keycodeState)
      pressState := ~released
    }
    is (false.B) {
      val pressed = (currOut(1) =/= 0xF0.U)
      pressCount := pressCount + pressed.asUInt
      pressState := pressed
      keycodeState := currOut(0)
    }
  }

  for (i <- 0 until 8) {
    io.segDisplay(i) := ~segDecode(i).io.segMsbA
  }
}
