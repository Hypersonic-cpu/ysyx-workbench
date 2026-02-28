#!/usr/bin/env python3
"""RTL CoreMark sweep across BPU configurations.

Runs CoreMark in SoC mode for each (bp_type, btb_size, ras_size) combo,
parses PMU output, and writes results to JSON.

Usage:
  python3 scripts/bpu_rtl_sweep.py --outdir bpu_sweep_results
"""

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

NPC_HOME = Path(os.environ.get("NPC_HOME", Path(__file__).resolve().parent.parent))
COREMARK_BIN = Path(os.environ.get(
    "COREMARK_BIN",
    Path(os.environ.get("AM_TEST", "")).parent / "benchmarks" / "coremark" /
    "build" / "coremark-riscv32e-ysyxsoc.bin"
))

CONFIGS = []
for bp_type in ["bimodal", "btfnt"]:
    for btb_size in [32, 64, 128, 256]:
        for ras_size in [0, 4]:
            CONFIGS.append({
                "bp_type": bp_type,
                "btb_size": btb_size,
                "ras_size": ras_size,
            })


def tag_of(cfg):
    return f"{cfg['bp_type']}_btb{cfg['btb_size']}_ras{cfg['ras_size']}"


def build_and_run(cfg, coremark_bin):
    tag = tag_of(cfg)
    bp_flag = f"--bp-{cfg['bp_type']}"
    rtl_args = f"{bp_flag} --bp-entries {cfg['btb_size']} --ras-size {cfg['ras_size']}"

    print(f"[{tag}] Building...", flush=True)
    # Remove old binary
    elf = NPC_HOME / "build-sim" / "rvproc" / "rvproc.elf"
    if elf.exists():
        elf.unlink()

    build_cmd = (
        f"make -C {NPC_HOME} compile DIFFENA=0 DPRINTF=1 LOGENA=0 "
        f"SOCMODE=1 DBGENA=0 RTL_SCALA_ARG=\"{rtl_args}\""
    )
    r = subprocess.run(build_cmd, shell=True, capture_output=True, text=True)
    if r.returncode != 0:
        print(f"[{tag}] BUILD FAILED", flush=True)
        return None

    print(f"[{tag}] Running CoreMark...", flush=True)
    run_cmd = (
        f"make -C {NPC_HOME} runonly SOCMODE=1 "
        f"mrombin={coremark_bin} simccargs=\"-M 100000000\""
    )
    r = subprocess.run(run_cmd, shell=True, capture_output=True, text=True)
    output = r.stdout + r.stderr

    result = {"tag": tag, **cfg}
    # Parse PMU stats
    m = re.search(r"Cycles\s+(\d+)", output)
    if m: result["cycles"] = int(m.group(1))
    m = re.search(r"InstRet\s+(\d+)\s+IPC\s+([\d.]+)", output)
    if m:
        result["inst_ret"] = int(m.group(1))
        result["ipc"] = float(m.group(2))
    m = re.search(r"BranchPred\s*:\s*samples\s+(\d+).*?Correct\s+(\d+)", output)
    if m:
        result["bp_accesses"] = int(m.group(1))
        result["bp_correct"] = int(m.group(2))
        result["bp_misses"] = result["bp_accesses"] - result["bp_correct"]
        result["bp_accuracy"] = result["bp_correct"] / result["bp_accesses"] if result["bp_accesses"] > 0 else 0
    m = re.search(r"WrongTgt\s+(\d+)", output)
    if m: result["wrong_tgt"] = int(m.group(1))
    m = re.search(r"WrongDir\s+(\d+)", output)
    if m: result["wrong_dir"] = int(m.group(1))
    m = re.search(r"BtbMiss\s+(\d+)", output)
    if m: result["btb_miss"] = int(m.group(1))
    m = re.search(r"CoreMark PASS\s+(\d+)\s+Marks", output)
    if m: result["coremark"] = int(m.group(1))
    m = re.search(r"BranchMispred\s+(\d+)", output)
    if m: result["mispred_stall_cyc"] = int(m.group(1))

    print(f"[{tag}] IPC={result.get('ipc','?')} "
          f"Acc={result.get('bp_accuracy',0)*100:.1f}% "
          f"CM={result.get('coremark','?')}", flush=True)
    return result


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--outdir", default="bpu_sweep_results")
    ap.add_argument("--coremark-bin", default=str(COREMARK_BIN))
    args = ap.parse_args()

    outdir = Path(args.outdir)
    outdir.mkdir(parents=True, exist_ok=True)

    results = []
    for cfg in CONFIGS:
        r = build_and_run(cfg, args.coremark_bin)
        if r:
            results.append(r)
            with open(outdir / "results.json", "w") as f:
                json.dump(results, f, indent=2)

    print(f"\nDone. {len(results)} configs completed.")
    print(f"Results: {outdir / 'results.json'}")


if __name__ == "__main__":
    main()
