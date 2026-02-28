import scala.util.Properties
import java.nio.file.Paths
import rvproc.cache.iCacheConf
import scala.collection.mutable.ArrayBuffer
import rvproc.{BTFNT, Bimodal, Extended, GlbCtrl, NoPred, Tiny}

object Elaborate extends App {
  println(s"-> Elaborate Argv: ${args.mkString(":")}")

  GlbCtrl.debug = true
  GlbCtrl.sta = false
  GlbCtrl.config = Extended

  def parseArgs(args: Array[String]) = {
    var isSocMode  = false
    var l1iSize    = -1
    var l1iBlksize = -1
    var l1iAssoc   = 1
    var l1dSize    = -1
    var l1dBlksize = -1
    val rest       = scala.collection.mutable.ArrayBuffer[String]()

    var i = 0
    while (i < args.length) {
      args(i) match {
        case "--soc-mode"        => isSocMode = true
        case "--l1i-size"        =>
          l1iSize = args(i + 1).toInt; i += 1
        case "--l1i-blksize"     =>
          l1iBlksize = args(i + 1).toInt; i += 1
        case "--l1i-assoc"       =>
          l1iAssoc = args(i + 1).toInt; i += 1
        case "--l1d-size"        =>
          l1dSize = args(i + 1).toInt; i += 1
        case "--l1d-blksize"     =>
          l1dBlksize = args(i + 1).toInt; i += 1
        case "--debug"           => GlbCtrl.debug = true
        case "--no-debug"        => GlbCtrl.debug = false
        case "--sta"             => GlbCtrl.sta = true
        case "--no-sta"          => GlbCtrl.sta = false
        case "--config-tiny"     =>
          GlbCtrl.config = Tiny
          GlbCtrl.bpEntries = 32
          GlbCtrl.rasSize = 0
        case "--config-extended" =>
          GlbCtrl.config = Extended
          GlbCtrl.bpEntries = 128
          GlbCtrl.rasSize = 4
        case "--bp-none"         => GlbCtrl.bpType = NoPred
        case "--bp-btfnt"        => GlbCtrl.bpType = BTFNT
        case "--bp-bimodal"      => GlbCtrl.bpType = Bimodal
        case "--bp-entries"      =>
          GlbCtrl.bpEntries = args(i + 1).toInt; i += 1
        case "--ras-size"        =>
          GlbCtrl.rasSize = args(i + 1).toInt; i += 1
        case other               => rest += other
      }
      i += 1
    }

    val cfgL1iSize = GlbCtrl.config match {
      case Tiny     => 128
      case Extended => 1024
    }
    val cfgL1iBlk  = 16
    val cfgL1dSize = GlbCtrl.config match {
      case Tiny     => 0
      case Extended => 1024
    }
    val cfgL1dBlk  = 16

    if (l1iSize < 0) l1iSize = cfgL1iSize
    if (l1iBlksize < 0) l1iBlksize = cfgL1iBlk
    if (l1dSize < 0) l1dSize = cfgL1dSize
    if (l1dBlksize < 0) l1dBlksize = cfgL1dBlk

    (
      isSocMode,
      iCacheConf(32, l1iSize, l1iBlksize, l1iAssoc),
      iCacheConf(32, l1dSize, l1dBlksize, 1),
      rest.toArray
    )
  }

  val (isSoC, l1iConfig, l1dConfig, restArgs) = parseArgs(args)

  l1iConfig.printConf()

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
    new rvproc.rvCoreWrapper(isSoC, l1iConfig, l1dConfig),
    restArgs,
    firtoolOptions
  )
}
