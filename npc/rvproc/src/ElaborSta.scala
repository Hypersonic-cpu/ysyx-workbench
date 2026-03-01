import scala.util.Properties
import java.nio.file.Paths
import rvproc.cache.iCacheConf
import rvproc.{BTFNT, Bimodal, Extended, GlbCtrl, NoPred, Tiny}

object ElaborSta extends App {
  GlbCtrl.debug = false
  GlbCtrl.sta = true
  GlbCtrl.config = Extended

  var l1iSize    = -1
  var l1iBlksize = -1
  var l1iAssoc   = 1
  var l1dSize    = -1
  var l1dBlksize = -1
  val rest       = scala.collection.mutable.ArrayBuffer[String]()

  var i = 0
  while (i < args.length) {
    args(i) match {
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
  if (l1iSize < 0) l1iSize = cfgL1iSize
  if (l1iBlksize < 0) l1iBlksize = 16
  if (l1dSize < 0)
    l1dSize =
      (if (GlbCtrl.hasDCache) 1024
       else 0)
  if (l1dBlksize < 0) l1dBlksize = 16

  val l1iConf = iCacheConf(32, l1iSize, l1iBlksize, l1iAssoc)
  val l1dConf = iCacheConf(32, l1dSize, l1dBlksize, 1)
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
    rest.toArray,
    firtoolOptions
  )
}
