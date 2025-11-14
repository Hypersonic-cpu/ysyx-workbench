import chisel3._
import circt.stage.ChiselStage
import circt.stage.FirtoolOption // 用于传递firtool选项
import chisel3.stage.ChiselGeneratorAnnotation // 明确导入 Chisel Generator Annotation
import java.nio.file.Paths

object Elaborate extends App {
  val npcHome = sys.env.get("NPC_HOME").get
  val outputDir = Paths.get(npcHome, "build-sv/rvproc/").toString()

  // 1. FIRTOOL 的命令行参数列表 (作为字符串数组)
  val firtoolOptionsString = Array(
    // s"-o=$outputDir", // 指定输出目录
    "--split-verilog", // 启用分文件输出
    "--top=rvCore", // 保持 Top 模块名称
    "--lowering-options=" + List(
      "disallowLocalVariables",
      "disallowPackedArrays",
      "locationInfoStyle=wrapInAtSquareBracket"
    ).reduce(_ + "," + _)
  )

  // 2. ChiselStage 的基本参数 (指定目标语言)
  val chiselArgs = Array(
    "--target", "systemverilog"
  )

  // 3. 关键：使用 execute API
  // 注意：在 Chisel 6.x 中，将 firtool 的命令行参数直接放在 ChiselStage.execute 的第一个参数数组中是推荐的做法。
  (new ChiselStage).execute(
    // 命令行参数：ChiselStage参数 + firtool参数
    chiselArgs ++ firtoolOptionsString, 
    // Annotations：指定要编译的模块
    Seq(
      // 明确告诉 ChiselStage 要生成哪个 Chisel 模块
      ChiselGeneratorAnnotation(() => new rvproc.rvCore())
      // 在新版本中，只要在第一个参数数组中包含了 -o 和 --split-verilog，
      // ChiselStage/CIRCT 就会自动启用多文件生成逻辑，无需额外的 EmitAllModulesAnnotation。
    )
  )
}
