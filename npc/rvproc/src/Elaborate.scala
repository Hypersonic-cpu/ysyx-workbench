import scala.util.Properties
import java.nio.file.Paths

object Elaborate extends App {

  val firtoolOptions = Array(
    "--split-verilog",
    "-o", "/home/kong/ysyx-workbench/npc/build-sv/rvproc/",
    "--disable-aggresive-merge-connections",
    "--disable-opt",
    "--lowering-options=" + List(
      // make yosys happy
      // see https://github.com/llvm/circt/blob/main/docs/VerilogGeneration.md
      "disallowLocalVariables",
      "disallowPackedArrays",
      "locationInfoStyle=wrapInAtSquareBracket",
      "emmitedLineLength=76",
    ).reduce(_ + "," + _)
  )

  circt.stage.ChiselStage.emitSystemVerilogFile(new rvproc.rvCore(), args, firtoolOptions)
}
