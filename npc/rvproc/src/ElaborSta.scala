import scala.util.Properties
import java.nio.file.Paths

object ElaborSta extends App {
val outputPath = "/home/kong/ysyx-workbench/npc/build-sv/rvproc/mcRvCore.sv"
  val firtoolOptions = Array(
    "-o", outputPath,
    "--lowering-options=" + List(
      // make yosys happy
      // see https://github.com/llvm/circt/blob/main/docs/VerilogGeneration.md
      "disallowLocalVariables",
      "disallowPackedArrays",
      "locationInfoStyle=wrapInAtSquareBracket",
      "emittedLineLength=72",
    ).reduce(_ + "," + _)
  )

  circt.stage.ChiselStage.emitSystemVerilogFile(new rvproc.rvCore(0x3000_0000L), args, firtoolOptions)
}
