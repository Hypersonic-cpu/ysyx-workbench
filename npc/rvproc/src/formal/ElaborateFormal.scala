// Elaboration entry point for iCache formal verification.
// Generates iCache as SystemVerilog with SVA assertions,
// to be consumed by the hand-written iCacheFormal.sv wrapper.

object ElaborateFormal extends App {
  val ysyxNPC   = System.getenv("NPC_HOME")
  assert(ysyxNPC.nonEmpty)
  val outputDir = ysyxNPC + "/formal/icache"
  // val outputDir = "/home/kong/ysyx-workbench/npc/formal/icache/"

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
    new rvproc.formal.iCacheFormalWrapper,
    args,
    firtoolOptions
  )
}
