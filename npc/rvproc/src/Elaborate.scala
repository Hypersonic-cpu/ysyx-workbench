import scala.util.control.NonFatal
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import rvproc.GlbCtrl
import rvproc.cache.CacheConf
import rvproc.AnsiColor._

object Elaborate extends App {
  println(s"-> Elaborate Argv: ${args.mkString(":")}")

  val cfg = ElaborConfig.parseArgs(
    args,
    debugDefault = true,
    staDefault = false
  )

  dumpElaborConfig(cfg)

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
    new rvproc.rvCoreWrapper(
      cfg.isSocMode,
      cfg.l1iConfig,
      cfg.l1dConfig
    ),
    cfg.restArgs,
    firtoolOptions
  )

  private def dumpElaborConfig(cfg: ElaborConfig): Unit = {
    Option(System.getenv("ELABORATE_CONFIG_OUT"))
      .filter(_.nonEmpty)
      .foreach { rawPath =>
        try {
          val target = Paths.get(rawPath)
          Option(target.getParent)
            .foreach(p => Files.createDirectories(p))
          Files.writeString(
            target,
            elaborConfigJson(cfg),
            StandardCharsets.UTF_8
          )
          println(
            s"ELABORATE_CONFIG_OUT -> $rawPath".green
          )
        } catch {
          case NonFatal(err) =>
            Console.err.println(
              s"[warn] Failed to dump ElaborConfig to $rawPath: ${err.getMessage}".red
            )
        }
      }
  }

  private def elaborConfigJson(cfg: ElaborConfig): String = {
    val fields = Seq(
      boolField("isSocMode", cfg.isSocMode),
      s""""glb":${glbCtrlJson}""",
      s""""l1iConfig":${cacheConfJson(cfg.l1iConfig)}""",
      s""""l1dConfig":${cacheConfJson(cfg.l1dConfig)}""",
      s""""restArgs":${restArgsJson(cfg.restArgs)}"""
    )
    fields.mkString("{", ",", "}")
  }

  private def glbCtrlJson: String = {
    val fields = Seq(
      boolField("debug", GlbCtrl.debug),
      boolField("sta", GlbCtrl.sta),
      boolField("cyclicPrint", GlbCtrl.cyclicPrint),
      strField("config", GlbCtrl.config.toString),
      strField("bpType", GlbCtrl.bpType.toString),
      intField("bpEntries", GlbCtrl.bpEntries),
      intField("btbEntries", GlbCtrl.btbEntries),
      intField("rasSize", GlbCtrl.rasSize),
      boolField("withPrefetch", GlbCtrl.withPrefetch)
    )
    fields.mkString("{", ",", "}")
  }

  private def cacheConfJson(conf: CacheConf): String = {
    val fields = Seq(
      intField("addrBits", conf.addrBits),
      intField("dataBytes", conf.dataBytes),
      intField("lineBytes", conf.lineBytes),
      intField("assoc", conf.assoc)
    )
    fields.mkString("{", ",", "}")
  }

  private def restArgsJson(args: Array[String]): String =
    args.map(strValue).mkString("[", ",", "]")

  private def boolField(name: String, value: Boolean): String =
    s""""$name":${if (value) "true" else "false"}"""

  private def intField(name: String, value: Int): String =
    s""""$name":$value"""

  private def strField(name: String, value: String): String =
    s""""$name":"${escapeJson(value)}""""

  private def strValue(value: String): String =
    s""""${escapeJson(value)}""""

  private def escapeJson(text: String): String =
    text.flatMap {
      case '\\' => "\\\\"
      case '"'  => "\\\""
      case '\r' => "\\r"
      case '\n' => "\\n"
      case '\t' => "\\t"
      case c    => s"$c"
    }
}
