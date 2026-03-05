import scala.util.Properties
import java.nio.file.Paths

object Elaborate extends App {
  println(s"-> Elaborate Argv: ${args.mkString(":")}")

  val cfg = ElaborConfig.parseArgs(args, debugDefault = true, staDefault = false)

  cfg.l1iConfig.printConf()

  val ysyxNPC    = System.getenv("NPC_HOME")
  assert(ysyxNPC.nonEmpty)
  val outputPath = ysyxNPC + "/build-sv/rvproc/"

  val firtoolOptions = Array(
    "--split-verilog",
    "-o",
    outputPath,
    "--lowering-options=" + List(
      "disallowLocalVariables",
      "disallowPackedArrays",
      "locationInfoStyle=wrapInAtSquareBracket",
      "emittedLineLength=76"
    ).reduce(_ + "," + _)
  )

  circt.stage.ChiselStage.emitSystemVerilogFile(
    new rvproc.rvCoreWrapper(cfg.isSocMode, cfg.l1iConfig, cfg.l1dConfig),
    cfg.restArgs,
    firtoolOptions
  )
}
