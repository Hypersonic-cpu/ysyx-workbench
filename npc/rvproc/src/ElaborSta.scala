import scala.util.Properties
import java.nio.file.Paths
import rvproc.cache.iCacheConf

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

  circt.stage.ChiselStage.emitSystemVerilogFile(new rvproc.rvCore(true, iCacheConf(32, 256, 32, 1)), args, firtoolOptions)
}
