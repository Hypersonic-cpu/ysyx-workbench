import rvproc.cache.CacheConf
import scala.collection.mutable.ArrayBuffer
import rvproc.{BTFNT, Bimodal, Extended, GlbCtrl, NoPred, Tiny}

case class ElaborConfig(
  isSocMode: Boolean,
  l1iConfig: CacheConf,
  l1dConfig: CacheConf,
  restArgs:  Array[String])

object ElaborConfig {
  def parseArgs(
    args:         Array[String],
    debugDefault: Boolean,
    staDefault:   Boolean
  ): ElaborConfig = {
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
          throw new IllegalArgumentException(
            "--config-tiny is removed; only Extended is supported"
          )
        case "--config-extended" =>
          GlbCtrl.config = Extended
          GlbCtrl.bpEntries = 256
          GlbCtrl.btbEntries = 128
          GlbCtrl.rasSize = 8
        case "--bp-none"         => GlbCtrl.bpType = NoPred
        case "--bp-btfnt"        => GlbCtrl.bpType = BTFNT
        case "--bp-bimodal"      => GlbCtrl.bpType = Bimodal
        case "--bp-entries"      =>
          GlbCtrl.bpEntries = args(i + 1).toInt; i += 1
        case "--btb-entries"     =>
          GlbCtrl.btbEntries = args(i + 1).toInt; i += 1
        case "--ras-size"        =>
          GlbCtrl.rasSize = args(i + 1).toInt; i += 1
        case "--with-prefetch"   =>
          GlbCtrl.withPrefetch = true
        case other               => rest += other
      }
      i += 1
    }

    // Set defaults after parsing
    GlbCtrl.debug = debugDefault
    GlbCtrl.sta = staDefault

    val cfgL1iSize = 2048
    val cfgL1iBlk  = 16
    val cfgL1dSize = 1024
    val cfgL1dBlk  = 16

    if (l1iSize < 0) l1iSize = cfgL1iSize
    if (l1iBlksize < 0) l1iBlksize = cfgL1iBlk
    if (l1dSize < 0) l1dSize = cfgL1dSize
    if (l1dBlksize < 0) l1dBlksize = cfgL1dBlk

    ElaborConfig(
      isSocMode,
      CacheConf(32, l1iSize, l1iBlksize, l1iAssoc),
      CacheConf(32, l1dSize, l1dBlksize, 1),
      rest.toArray
    )
  }
}
