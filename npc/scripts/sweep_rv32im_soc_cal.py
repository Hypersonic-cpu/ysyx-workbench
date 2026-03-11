#!/usr/bin/env python3
"""sweep_rv32im_soc_cal.py -- RTL sweep for RV32IM SoC calibration.

Serial compile+run via `make ARCH=riscv32im-ysyxsoc ... run`.
Stats saved to $NPC_HOME/ccout/<prefix>/<tag>/stats.json.

Sweep groups:
  bpu:   NoBPU + Bimodal BTB={64,128,512} x BHT={128,256,1024}
         (fixed iCache 1kB/16B, dCache 1kB/16B)
  cache: iCache {512,1024,2048} x blk {16,32}
         dCache {512,1024} x blk 16
         (fixed BPU bimodal h256-t128)
  pipe:  large caches (4kB/16B) + NoBPU (pipeline validation)

Usage:
  python3 scripts/sweep_rv32im_soc_cal.py --mhz 1000 --group all
  python3 scripts/sweep_rv32im_soc_cal.py --mhz 500 1000 --group bpu
"""

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

NPC_HOME = Path(os.environ.get(
    "NPC_HOME", Path(__file__).resolve().parent.parent))
AM_BENCH = Path(os.environ["AM_BENCH"]) if "AM_BENCH" in os.environ \
    else Path(os.environ.get(
        "AM_HOME", "")).parent / "am-kernels" / "benchmarks"
COREMARK_DIR = None  # resolved in main()

IC_ASSOC = 1
DC_ASSOC = 1
DC_BLK = 16


def make_prefix(mhz):
    return f"rv32im_soc_cal_{mhz}MHz"


def canonical_tag(ic_sz, ic_blk, dc_sz, bpu_type,
                  bht=0, btb=0):
    s = (f"coremark_l1i-{ic_sz}-b{ic_blk}-a{IC_ASSOC}"
         f"_l1d-{dc_sz}-b{DC_BLK}-a{DC_ASSOC}")
    if bpu_type == "none":
        s += "_bpu-none"
    else:
        s += f"_bpu-bimodal-h{bht}-t{btb}"
    return s


def build_rtl_scala_arg(ic_sz, ic_blk, dc_sz, bpu_type,
                        bht=0, btb=0, ras=8):
    parts = [
        "--config-extended",
        f"--l1i-size {ic_sz}", f"--l1i-blksize {ic_blk}",
        f"--l1d-size {dc_sz}", f"--l1d-blksize {DC_BLK}",
    ]
    if bpu_type == "none":
        parts.append("--bp-none")
    elif bpu_type == "bimodal":
        parts += [
            "--bp-bimodal",
            f"--bp-entries {bht}",
            f"--btb-entries {btb}",
            f"--ras-size {ras}",
        ]
    return " ".join(parts)


def run_config(tag, prefix, ic_sz, ic_blk, dc_sz, bpu_type,
               bht, btb, mhz, coremark_dir):
    """Compile + run one config via make run. Returns (tag, ok)."""
    stats_path = NPC_HOME / "ccout" / prefix / tag / "stats.json"
    if stats_path.exists():
        print(f"  [SKIP] {tag} (stats exist)", flush=True)
        return tag, True

    rtl_args = build_rtl_scala_arg(ic_sz, ic_blk, dc_sz,
                                   bpu_type, bht, btb)
    r_path = f"{prefix}/{tag}"

    cmd = (
        f"make ARCH=riscv32im-ysyxsoc MHZ={mhz} "
        f"LOGENA=0 DIFFENA=0 DPRINTF=0 NVBENA=0 DBGENA=1 "
        f'RTL_SCALA_ARG="{rtl_args}" '
        f'simccargs="-R {r_path}" run'
    )
    print(f"  [RUN] {tag} ...", flush=True)
    r = subprocess.run(
        cmd, shell=True, capture_output=True, text=True,
        cwd=str(coremark_dir), timeout=1200)
    if r.returncode != 0:
        err = (r.stdout + r.stderr)[-600:]
        print(f"  [FAIL] {tag}:\n{err}", flush=True)
        return tag, False
    if not stats_path.exists():
        print(f"  [FAIL] {tag}: no stats.json", flush=True)
        return tag, False

    with open(stats_path) as f:
        d = json.load(f)
    ipc = d["pmu"]["ipc"]
    print(f"  [OK]   {tag}  IPC={ipc:.4f}", flush=True)
    return tag, True


def gen_bpu_configs():
    """NoBPU + Bimodal BPU sweep (fixed 1kB caches)."""
    ic, blk, dc = 1024, 16, 1024
    cfgs = [("none", 0, 0)]
    for bht in [128, 256, 1024]:
        for btb in [64, 128, 512]:
            cfgs.append(("bimodal", bht, btb))
    return [(canonical_tag(ic, blk, dc, bp, bht, btb),
             ic, blk, dc, bp, bht, btb) for bp, bht, btb in cfgs]


def gen_cache_configs():
    """iCache x dCache sweep (fixed bimodal h256-t128)."""
    bht, btb = 256, 128
    cfgs = []
    for ic in [512, 1024, 2048]:
        for blk in [16, 32]:
            for dc in [512, 1024]:
                tag = canonical_tag(ic, blk, dc, "bimodal",
                                    bht, btb)
                cfgs.append((tag, ic, blk, dc, "bimodal",
                             bht, btb))
    return cfgs


def gen_pipe_configs():
    """Large cache + NoBPU for pipeline validation."""
    ic, blk, dc = 4096, 16, 2048
    tag = canonical_tag(ic, blk, dc, "none")
    return [(tag, ic, blk, dc, "none", 0, 0)]


def main():
    ap = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--group",
                    choices=["bpu", "cache", "pipe", "all"],
                    default="all")
    ap.add_argument("--mhz", type=int, nargs="+",
                    default=[1000],
                    help="Frequency(s) to sweep")
    args = ap.parse_args()

    # Resolve coremark dir
    for candidate in [
        Path(os.environ.get("AM_BENCH", "")) / "coremark",
        Path("/home/kong/ysyx-workbench/am-kernels/benchmarks"
             "/coremark"),
    ]:
        if (candidate / "Makefile").exists():
            coremark_dir = candidate
            break
    else:
        print("ERROR: cannot find coremark dir. "
              "Set AM_BENCH env var.")
        sys.exit(1)

    configs_base = []
    if args.group in ("bpu", "all"):
        configs_base += gen_bpu_configs()
    if args.group in ("cache", "all"):
        configs_base += gen_cache_configs()
    if args.group in ("pipe", "all"):
        configs_base += gen_pipe_configs()

    # Deduplicate
    seen = set()
    configs_base = [c for c in configs_base
                    if c[0] not in seen and not seen.add(c[0])]

    for mhz in args.mhz:
        prefix = make_prefix(mhz)
        print(f"\n{'='*60}")
        print(f"  {prefix}: {len(configs_base)} configs @ {mhz} MHz")
        print(f"{'='*60}")

        ok_n = 0
        for tag, ic, blk, dc, bp, bht, btb in configs_base:
            _, ok = run_config(tag, prefix, ic, blk, dc, bp,
                               bht, btb, mhz, coremark_dir)
            if ok:
                ok_n += 1

        print(f"\n  {ok_n}/{len(configs_base)} succeeded. "
              f"Stats in {NPC_HOME}/ccout/{prefix}/")


if __name__ == "__main__":
    main()
