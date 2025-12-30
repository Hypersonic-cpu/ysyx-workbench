import scala.util.Properties
import java.nio.file.Paths

object Elaborate extends App {
  val isSocMode    = args.contains("--soc-mode")
  val filteredArgs = args.filter(_ != "--soc-mode")
  val resetVector  = if (isSocMode) 0x30000000L else 0x80000000L

  val outputPath = "/home/kong/ysyx-workbench/npc/build-sv/rvproc/"

  val firtoolOptions = Array(
    "--split-verilog",
    "-o",
    outputPath,
    // "--disable-aggressive-merge-connections",
    // "--disable-opt",
    // "--preserve-values=all",
    "--lowering-options=" + List(
      // make yosys happy
      // see https://github.com/llvm/circt/blob/main/docs/VerilogGeneration.md
      "disallowLocalVariables",
      "disallowPackedArrays",
      "locationInfoStyle=wrapInAtSquareBracket",
      "emittedLineLength=76"
    ).reduce(_ + "," + _)
  )

  circt.stage.ChiselStage.emitSystemVerilogFile(
    new rvproc.rvCoreWrapper(resetVector),
    filteredArgs,
    firtoolOptions
  )
}
