#!/usr/bin/env python3
"""sweep_cm2_cfg_ext.py — RTL CoreMark sweep for --config-extended.

Sweeps iCache × dCache × BPU at a given frequency. Serial compile,
parallel run. Output uses canonical naming compatible with npSim
visual/plot_*.py scripts.

Usage:
  python3 scripts/sweep_cm2_cfg_ext.py --mhz 1000
  python3 scripts/sweep_cm2_cfg_ext.py --mhz 500 --skip-compile
  python3 scripts/sweep_cm2_cfg_ext.py --mhz 1000 --skip-compile --skip-run
"""

import argparse
import json
import os
import subprocess
from pathlib import Path
from concurrent.futures import ProcessPoolExecutor, as_completed
from itertools import product as cartesian

NPC_HOME = Path(os.environ.get(
    "NPC_HOME", Path(__file__).resolve().parent.parent))
AM_BENCH = Path(os.environ.get("AM_TEST", "")).parent / "benchmarks"
COREMARK_BIN = AM_BENCH / "coremark" / "build" \
    / "coremark-riscv32e-ysyxsoc.bin"

# ── Sweep axes ─────────────────────────────────────────────────────────────
IC_SIZES  = [512, 1024, 2048, 4096]
IC_BLKS   = [16, 32]
IC_ASSOC  = 1

DC_SIZES  = [512, 1024]
DC_BLK    = 16
DC_ASSOC  = 1

BTB_SIZES = [64, 128, 256]   # BHT = BTB (tied, matching cfg_ext_valid.py)

MAX_CYCLES = 500_000_000


def canonical_suffix(ic_sz, ic_blk, dc_sz, btb):
    return (f"l1i-{ic_sz}-b{ic_blk}-a{IC_ASSOC}"
            f"_l1d-{dc_sz}-b{DC_BLK}-a{DC_ASSOC}"
            f"_bpu-bimodal-h{btb}-t{btb}")


def make_tag(prefix, ic_sz, ic_blk, dc_sz, btb):
    return f"{prefix}/{prefix}_{canonical_suffix(ic_sz, ic_blk, dc_sz, btb)}"


def elf_name(mhz, ic_sz, ic_blk, dc_sz, btb):
    suf = canonical_suffix(ic_sz, ic_blk, dc_sz, btb)
    return f"rvproc_{mhz}_{suf}.elf"


def compile_one(ic_sz, ic_blk, dc_sz, btb, mhz, prefix):
    """Compile one RTL config. Returns (tag, elf_path) or None."""
    tag = make_tag(prefix, ic_sz, ic_blk, dc_sz, btb)
    elf_dst = NPC_HOME / "build-sim" / "rvproc" / elf_name(
        mhz, ic_sz, ic_blk, dc_sz, btb)

    if elf_dst.exists():
        print(f"[SKIP] {tag} (elf exists)")
        return (tag, str(elf_dst))

    rtl_args = (
        f"--config-extended --bp-bimodal "
        f"--bp-entries {btb} --btb-entries {btb} "
        f"--l1i-size {ic_sz} --l1i-blksize {ic_blk} "
        f"--l1d-size {dc_sz} --l1d-blksize {DC_BLK}")

    cmd = (
        f"make -C {NPC_HOME} compile DIFFENA=0 DPRINTF=0 "
        f"LOGENA=0 SOCMODE=1 DBGENA=1 NVBENA=0 MHZ={mhz} "
        f'RTL_SCALA_ARG="{rtl_args}"')

    print(f"[COMPILE] {tag} ...", flush=True)
    r = subprocess.run(
        cmd, shell=True, capture_output=True, text=True, timeout=600)
    if r.returncode != 0:
        print(f"[FAIL] {tag}:\n{r.stderr[-500:]}", flush=True)
        return None

    src = NPC_HOME / "build-sim" / "rvproc" / "rvproc.elf"
    if src.exists():
        src.rename(elf_dst)
        print(f"[OK] {tag}")
        return (tag, str(elf_dst))
    print(f"[FAIL] {tag}: no elf produced")
    return None


def run_one(tag, elf_path, cm_bin):
    """Run one RTL sim. Returns (tag, stats_dict) or None."""
    outdir = NPC_HOME / "ccout" / tag
    outdir.mkdir(parents=True, exist_ok=True)
    cmd = f"{elf_path} {cm_bin} -M {MAX_CYCLES} -R {tag}"
    print(f"[RUN] {tag}", flush=True)
    r = subprocess.run(
        cmd, shell=True, capture_output=True, text=True, timeout=600)
    sf = outdir / "stats.json"
    if not sf.exists():
        print(f"[FAIL] {tag}: no stats")
        return None
    with open(sf) as f:
        d = json.load(f)
    ipc = d["pmu"]["ipc"]
    print(f"[DONE] {tag} IPC={ipc:.4f}")
    return (tag, d)


def main():
    ap = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--mhz", type=int, default=1000)
    ap.add_argument("--skip-compile", action="store_true")
    ap.add_argument("--skip-run", action="store_true")
    ap.add_argument("--max-parallel", type=int, default=3)
    ap.add_argument("--coremark-bin", type=str,
                    default=str(COREMARK_BIN))
    args = ap.parse_args()

    prefix = f"cm2_{args.mhz}MHz_cfg-ext"
    configs = list(cartesian(IC_SIZES, IC_BLKS, DC_SIZES, BTB_SIZES))
    print(f"Sweep: {len(configs)} configs, prefix={prefix}, MHZ={args.mhz}")

    # ── Serial compile ────────────────────────────────────────────────────
    elf_map = {}
    if not args.skip_compile:
        for ic_sz, ic_blk, dc_sz, btb in configs:
            result = compile_one(
                ic_sz, ic_blk, dc_sz, btb, args.mhz, prefix)
            if result:
                elf_map[result[0]] = result[1]
    else:
        for ic_sz, ic_blk, dc_sz, btb in configs:
            tag = make_tag(prefix, ic_sz, ic_blk, dc_sz, btb)
            elf = NPC_HOME / "build-sim" / "rvproc" / elf_name(
                args.mhz, ic_sz, ic_blk, dc_sz, btb)
            if elf.exists():
                elf_map[tag] = str(elf)
            else:
                print(f"[WARN] No elf for {tag}")

    if args.skip_run:
        print(f"Skip run. {len(elf_map)} elfs ready.")
        return

    # ── Parallel run ──────────────────────────────────────────────────────
    results = {}
    with ProcessPoolExecutor(max_workers=args.max_parallel) as pool:
        futs = {}
        for ic_sz, ic_blk, dc_sz, btb in configs:
            tag = make_tag(prefix, ic_sz, ic_blk, dc_sz, btb)
            if tag in elf_map:
                f = pool.submit(
                    run_one, tag, elf_map[tag], args.coremark_bin)
                futs[f] = tag
        for f in as_completed(futs):
            r = f.result()
            if r:
                results[r[0]] = r[1]

    # ── Summary ───────────────────────────────────────────────────────────
    print(f"\n{'Config':70s} {'IPC':>8s}")
    print("-" * 80)
    for ic_sz, ic_blk, dc_sz, btb in configs:
        tag = make_tag(prefix, ic_sz, ic_blk, dc_sz, btb)
        if tag in results:
            ipc = results[tag]["pmu"]["ipc"]
            print(f"{tag:70s} {ipc:8.4f}")
        else:
            print(f"{tag:70s} {'N/A':>8s}")


if __name__ == "__main__":
    main()
