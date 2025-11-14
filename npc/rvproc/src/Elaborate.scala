import scala.util.Properties
import java.nio.file.Paths

object Elaborate extends App {
  val npcHome = sys.env.get("NPC_HOME").get

  val firtoolOptions = Array(
    "-o", Paths.get(npcHome, "build-sv/rvproc/").toString(),
    "--split-verilog",
    "--top=rvCore",
    "--lowering-options=" + List(
      // make yosys happy
      // see https://github.com/llvm/circt/blob/main/docs/VerilogGeneration.md
      "disallowLocalVariables",
      "disallowPackedArrays",
      "locationInfoStyle=wrapInAtSquareBracket"
    ).reduce(_ + "," + _)
  )

  // val print = {
  //   printf(s"write to ${Paths.get(npcHome, "build-sv/rvproc/").toString()}\n")
  //   1
  // }

  circt.stage.ChiselStage.emitSystemVerilogFile(new rvproc.rvCore(), args, firtoolOptions)
}
