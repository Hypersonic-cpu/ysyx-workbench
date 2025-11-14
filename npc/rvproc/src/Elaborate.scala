import scala.util.Properties
import java.nio.file.Paths

object Elaborate extends App {
  val npcHome = sys.env.get("NPC_HOME").get

  val firtoolOptions = Array(
    "-o", Paths.get(npcHome, "npc/build-sv/rvproc/").toString(),
    "--split-verilog",
    "--lowering-options=" + List(
      // make yosys happy
      // see https://github.com/llvm/circt/blob/main/docs/VerilogGeneration.md
      "disallowLocalVariables",
      "disallowPackedArrays",
      "locationInfoStyle=wrapInAtSquareBracket"
    ).reduce(_ + "," + _)
  )
  circt.stage.ChiselStage.emitSystemVerilogFile(new rvproc.rvCore(), args, firtoolOptions)
}
