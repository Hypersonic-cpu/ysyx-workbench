object Elaborate extends App {
  val firtoolOptions = Array(
    "-o" , "/home/kong/ysyx-workbench/npc/build-sv/rvproc/", 
    "--split-verilog",
    "--lowering-options=" + List(
      // make yosys happy
      // see https://github.com/llvm/circt/blob/main/docs/VerilogGeneration.md
      "disallowLocalVariables",
      "disallowPackedArrays",
      "locationInfoStyle=wrapInAtSquareBracket"
    ).reduce(_ + "," + _)
  )
  circt.stage.ChiselStage.emitSystemVerilogFile(new rvproc.rvCore(), args, firtoolOptions)
}
