import scala.util.Properties
import java.nio.file.Paths
import rvproc.cache.iCacheConf
import rvproc.GlbCtrl

object ElaborSta extends App {
  // Defaults for STA: debug=false, sta=true
  GlbCtrl.debug = false
  GlbCtrl.sta = true
  GlbCtrl.sramlib = false

  var l1iSize    = 512
  var l1iBlksize = 16
  var l1iAssoc   = 1
  val rest = scala.collection.mutable.ArrayBuffer[String]()

  var i = 0
  while (i < args.length) {
    args(i) match {
      case "--l1i-size"    => l1iSize = args(i + 1).toInt; i += 1
      case "--l1i-blksize" => l1iBlksize = args(i + 1).toInt; i += 1
      case "--l1i-assoc"   => l1iAssoc = args(i + 1).toInt; i += 1
      case "--debug"       => GlbCtrl.debug = true
      case "--no-debug"    => GlbCtrl.debug = false
      case "--sta"         => GlbCtrl.sta = true
      case "--no-sta"      => GlbCtrl.sta = false
      case "--sramlib"     => GlbCtrl.sramlib = true
      case "--no-sramlib"  => GlbCtrl.sramlib = false
      case other           => rest += other
    }
    i += 1
  }

  val l1iConf = iCacheConf(32, l1iSize, l1iBlksize, l1iAssoc)
  l1iConf.printConf()

  val ysyxNPC = System.getenv("NPC_HOME")
  assert(ysyxNPC != null && ysyxNPC.nonEmpty)
  val outputPath =
    ysyxNPC + "/build-sv/rvproc/mcRvCore.sv"
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

  circt.stage.ChiselStage.emitSystemVerilogFile(
    new rvproc.rvCore(true, l1iConf),
    rest.toArray,
    firtoolOptions
  )
}
