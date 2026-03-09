// Elaboration entry point for IntMultiplier formal verification.

object ElaborateFormalMul extends App {
  val ysyxNPC   = System.getenv("NPC_HOME")
  assert(ysyxNPC.nonEmpty)
  val outputDir = ysyxNPC + "/formal/mul"
  // val outputDir = "/home/kong/ysyx-workbench/npc/formal/mul/"

  val firtoolOptions = Array(
    "--split-verilog",
    "-o",
    outputDir,
    "--verification-flavor=immediate",
    "--lowering-options=" + List(
      "disallowLocalVariables",
      "disallowPackedArrays",
      "locationInfoStyle=wrapInAtSquareBracket",
      "verifLabels",
      "emittedLineLength=76"
    ).reduce(_ + "," + _)
  )

  circt.stage.ChiselStage.emitSystemVerilogFile(
    new rvproc.formal.IntMulFormalWrapper,
    args,
    firtoolOptions
  )
}
