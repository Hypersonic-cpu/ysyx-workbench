package NjuProjN7

import chisel3._
import chisel3.util._

class Ps2Detector extends Module {
  val io = IO(new Bundle{
    val ps2Clk = Input(Bool())
    val ps2Dat = Input(Bool())
    val acqOut = Input(Bool())
    val outEn = Output(Bool())
    val outDt = Output(UInt(8.W))
    val oOvfl = Output(Bool())
  })

  // Shift register, fill in LSB
  val inDatSeq     = RegInit(0.U(10.W))
  val outBuffer    = Reg(Vec(8, UInt(8.W)))
  val bufReadPtr   = RegInit(7.U(3.W))
  val bufWritePtr  = RegInit(0.U(3.W))
  val bufOverflow  = RegInit(false.B)
  val inClkSmp = RegInit(0.U(3.W))
  inClkSmp := inClkSmp(1, 0) ## io.ps2Clk
  val inFallingEdge = inClkSmp(2) & (~inClkSmp(1));

  val outReady = RegInit(false.B)
  io.outEn := outReady
  io.outDt := outBuffer(bufReadPtr)
  io.oOvfl := bufOverflow
  val inCount = RegInit(0.U(4.W))
  
  val debugCnt = RegInit(0.U(2.W))
  when (inFallingEdge) {
    printf(cf"[$debugCnt%b] Cnt $inCount%d B ${io.ps2Dat}%d Cum ${inDatSeq}%x\n")
    debugCnt := debugCnt + 1.U
  }
  when (outReady & io.acqOut) {
    // We can process one output per cycle
    bufReadPtr := Mux(io.ps2Dat, bufReadPtr+1.U, bufReadPtr)
    outReady := ((bufReadPtr + 1.U)(2, 0) =/= bufWritePtr)
  }

  when (inFallingEdge) {
    when (inCount === 10.U && 
      ~inDatSeq(0) && // Start flag = 0
      io.ps2Dat &&    // Stop flag = 1
      (inDatSeq(9, 1).xorR)) {
      outBuffer(bufWritePtr) := inDatSeq(8, 1)
      bufWritePtr := bufWritePtr + 1.U
      outReady := true.B
      inCount := 0.U
      bufOverflow := bufOverflow | 
        ((bufWritePtr + 1.U)(2, 0) === bufReadPtr)
    } .elsewhen (inCount === 10.U) {
      inCount := 0.U
    } .otherwise {
      val mask = (1.U(10.W) << inCount)(9, 0)
      inDatSeq := (inDatSeq & ~mask) | Mux(io.ps2Dat, mask, 0.U)
      inCount := inCount + 1.U
    }
  }
}
