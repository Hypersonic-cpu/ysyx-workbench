import scala.util.Properties
import java.nio.file.Paths

object ElaborSta extends App {
  val cfg = ElaborConfig.parseArgs(args, debugDefault = false, staDefault = true)

  val (l1iConf, l1dConf) = (cfg.l1iConfig, cfg.l1dConfig)
  l1iConf.printConf()

  val ysyxNPC        = System.getenv("NPC_HOME")
  assert(ysyxNPC != null && ysyxNPC.nonEmpty)
  val outputPath     =
    ysyxNPC + "/build-sv/rvproc/mcRvCore.sv"
  val firtoolOptions = Array(
    "-o",
    outputPath,
    "--lowering-options=" + List(
      // make yosys happy
      // see https://github.com/llvm/circt/blob/main/docs/VerilogGeneration.md
      "disallowLocalVariables",
      "disallowPackedArrays",
      "locationInfoStyle=wrapInAtSquareBracket",
      "emittedLineLength=72"
    ).reduce(_ + "," + _)
  )

  circt.stage.ChiselStage.emitSystemVerilogFile(
    new rvproc.rvCore(true, l1iConf, l1dConf),
    cfg.restArgs,
    firtoolOptions
  )
}
