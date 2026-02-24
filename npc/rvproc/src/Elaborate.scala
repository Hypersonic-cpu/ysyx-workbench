import scala.util.Properties
import java.nio.file.Paths
import rvproc.cache.iCacheConf
import scala.collection.mutable.ArrayBuffer

object Elaborate extends App {
  println(s"-> Elaborate Argv: {args}")

  def parseArgs(args: Array[String]) = {
    var isSocMode  = false
    var l1iSize    = 1024
    var l1iBlksize = 16
    var l1iAssoc   = 1
    val rest       = scala.collection.mutable.ArrayBuffer[String]()

    var i = 0
    while (i < args.length) {
      args(i) match {
        case "--soc-mode"    => isSocMode = true
        case "--l1i-size"    => l1iSize = args(i + 1).toInt; i += 1
        case "--l1i-blksize" => l1iBlksize = args(i + 1).toInt; i += 1
        case "--l1i-assoc"   => l1iAssoc = args(i + 1).toInt; i += 1
        case other           => rest += other
      }
      i += 1
    }

    (
      isSocMode,
      iCacheConf(32, l1iSize, l1iBlksize, l1iAssoc),
      rest.toArray
    )
  }

  val (isSoC, l1iConfig, restArgs) = parseArgs(args)

  l1iConfig.printConf()

  val ysyxNPC = System.getenv("NPC_HOME")
  assert(ysyxNPC.nonEmpty)
  val outputPath = ysyxNPC + "/build-sv/rvproc/"

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
    new rvproc.rvCoreWrapper(isSoC, l1iConfig),
    restArgs,
    firtoolOptions
  )
}
