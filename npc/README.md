# NPC — RV32E 5-Stage Pipeline Core

RV32E in-order pipeline in Chisel 7.0.0-M2 (Scala 2.13.14), with
iCache, AXI interconnect, and DiffTest against NEMU.

## Quick Reference

```bash
make test              # Run ScalaTest specs
make verilog           # Chisel → SV (debug mode, build-sv/rvproc/)
make rtlsta            # Chisel → SV (STA mode, no debug wires)
make sramlib           # Generate SRAM .lib/.sv for all cache configs
make compile           # Verilog + Verilator compile
make run               # Full build + run simulator
make runonly            # Re-run without rebuild
make formal-icache     # SymbiYosys BMC for iCache
make checkformat       # Check Scalafmt (CI-enforced)
make reformat          # Auto-format Scala
```

### Cache configuration

Pass cache params via `RTL_SCALA_ARG`:
```bash
make verilog RTL_SCALA_ARG="--l1i-size 512 --l1i-blksize 32 --sramlib"
make rtlsta  RTL_SCALA_ARG="--l1i-size 512 --l1i-blksize 16 --sramlib"
```

`GlbCtrl` flags are auto-set per target:
- `make verilog`: `--debug --no-sta`
- `make rtlsta`: `--no-debug --sta`

### Simulation flags

| Flag | Default | Meaning |
|------|---------|---------|
| `LOGENA` | `0` | FST waveform output |
| `DIFFENA` | `1` | DiffTest against NEMU |
| `DBGENA` | `1` | RTL assertions |
| `SOCMODE` | `0` | 1=SoC topology, 0=standalone PMemBox |

### STA with yosys

```bash
cd $YOSYS_HOME
# DFF mode (no --sramlib):
make syn O=out/dff-512-16 \
  RTL_FILES="$(find $NPC_HOME/build-sv/rvproc -name '*.sv')" \
  DESIGN=rvCore CLK_FREQ_MHZ=5000 CLK_PORT_NAME=clock

# SRAM mode (--sramlib): set SRAM_BB_V and SRAM_LIB
make syn O=out/sram-512-16 \
  RTL_FILES="$(find $NPC_HOME/build-sv/rvproc -name '*.sv')" \
  DESIGN=rvCore CLK_FREQ_MHZ=5000 CLK_PORT_NAME=clock \
  SRAM_BB_V=$NPC_HOME/build-sv/rvproc/sram_blackbox.sv \
  SRAM_LIB=$NPC_HOME/build-sv/rvproc/sram_1rw.lib
```

After `make rtlsta --sramlib`, `build-sv/rvproc/` contains the
blackbox stubs (`sram_blackbox.sv`) and Liberty file (`sram_1rw.lib`)
needed by yosys.

## Architecture

Five stages: IFU → IDU → EXU → LSU → WBU, connected with
`BusConnect(..., Pipeline)` (registered handshake).

- **iCache**: Direct-mapped, 3-cycle read pipeline. Valid bit stored
  as a separate DFF array (requires reset). Configurable via
  `iCacheConf(addrWidth, dataBytes, lineBytes, assoc)`.
  `GlbCtrl.sramlib` selects OpenRAM SRAM BlackBox vs SyncReadMem.
- **AXI subsystem**: AXIArbiter (R/W) → AXIXBar (address decode)
- **SoC mode** (`SOCMODE=1`): reset `0x3000_0000`, external AXI master
- **Standalone** (`SOCMODE=0`): reset `0x8000_0000`, PMemBox DPI-C
