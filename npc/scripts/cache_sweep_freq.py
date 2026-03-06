#!/usr/bin/env python3
"""RTL cache sweep at a given frequency.

Runs iCache and dCache sweeps (6 configs each) with fixed
BPU (bimodal, btb128, bp256, ras8). Serial compile, parallel
run (max 3).

Usage:
  python3 scripts/cache_sweep_freq.py --mhz 500
  python3 scripts/cache_sweep_freq.py --mhz 500 --skip-compile
"""

import argparse
import json
import os
import subprocess
from pathlib import Path
from concurrent.futures import ProcessPoolExecutor, as_completed

NPC_HOME = Path(os.environ.get(
    "NPC_HOME",
    Path(__file__).resolve().parent.parent))
COREMARK = Path(os.environ.get("AM_TEST", "")).parent \
    / "benchmarks" / "coremark" / "build" \
    / "coremark-riscv32e-ysyxsoc.bin"

BPU = {"btb": 128, "bp": 256, "ras": 8}
SIZES = [256, 512, 1024]
BLKS = [16, 32]


def make_tag(kind, sz, blk, mhz):
    pre = "ic" if kind == "icache" else "dc"
    return f"{kind}-cal-{mhz}/{pre}{sz}_ln{blk}"


def compile_one(kind, sz, blk, mhz):
    """Compile one RTL config. Returns (tag, elf_path) or None."""
    tag = make_tag(kind, sz, blk, mhz)
    elf_dst = NPC_HOME / "build-sim" / "rvproc" / \
        f"rvproc_{tag.replace('/', '_')}.elf"
    if elf_dst.exists():
        print(f"[SKIP] {tag} (already compiled)")
        return (tag, str(elf_dst))

    if kind == "icache":
        ic_sz, ic_bl = sz, blk
        dc_sz, dc_bl = 1024, 16
    else:
        ic_sz, ic_bl = 1024, 16
        dc_sz, dc_bl = sz, blk

    rtl_args = (
        f"--config-extended --bp-bimodal "
        f"--bp-entries {BPU['bp']} "
        f"--btb-entries {BPU['btb']} "
        f"--ras-size {BPU['ras']} "
        f"--l1i-size {ic_sz} --l1i-blksize {ic_bl} "
        f"--l1d-size {dc_sz} --l1d-blksize {dc_bl}")
    cmd = (
        f"make -C {NPC_HOME} compile DIFFENA=0 DPRINTF=0 "
        f"LOGENA=0 SOCMODE=1 DBGENA=1 NVBENA=0 MHZ={mhz} "
        f'RTL_SCALA_ARG="{rtl_args}"')
    print(f"[COMPILE] {tag} ...", flush=True)
    r = subprocess.run(
        cmd, shell=True, capture_output=True, text=True,
        timeout=300)
    if r.returncode != 0:
        print(f"[FAIL] {tag}:\n{r.stderr[-300:]}", flush=True)
        return None
    src = NPC_HOME / "build-sim" / "rvproc" / "rvproc.elf"
    if src.exists():
        src.rename(elf_dst)
        print(f"[OK] {tag}", flush=True)
        return (tag, str(elf_dst))
    print(f"[FAIL] {tag}: no elf produced", flush=True)
    return None


def run_one(tag, elf_path, cm_bin, mhz):
    """Run one RTL sim. Returns (tag, stats_dict)."""
    outdir = NPC_HOME / "ccout" / tag
    outdir.mkdir(parents=True, exist_ok=True)
    cmd = f"{elf_path} {cm_bin} -M 500000000 -R {tag}"
    print(f"[RUN] {tag}", flush=True)
    r = subprocess.run(
        cmd, shell=True, capture_output=True, text=True,
        timeout=300)
    sf = outdir / "stats.json"
    if not sf.exists():
        print(f"[FAIL] {tag}: no stats", flush=True)
        return None
    with open(sf) as f:
        d = json.load(f)
    ipc = d["pmu"]["ipc"]
    print(f"[DONE] {tag} IPC={ipc:.4f}", flush=True)
    return (tag, d)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mhz", type=int, default=500)
    ap.add_argument("--skip-compile", action="store_true")
    ap.add_argument("--max-parallel", type=int, default=3)
    ap.add_argument("--coremark-bin", type=str,
                    default=str(COREMARK))
    args = ap.parse_args()

    configs = []
    for kind in ["icache", "dcache"]:
        for sz in SIZES:
            for blk in BLKS:
                configs.append((kind, sz, blk))

    # Serial compile
    elf_map = {}
    if not args.skip_compile:
        for kind, sz, blk in configs:
            result = compile_one(kind, sz, blk, args.mhz)
            if result:
                elf_map[result[0]] = result[1]
    else:
        for kind, sz, blk in configs:
            tag = make_tag(kind, sz, blk, args.mhz)
            elf_name = f"rvproc_{tag.replace('/', '_')}.elf"
            elf = NPC_HOME / "build-sim" / "rvproc" / elf_name
            if elf.exists():
                elf_map[tag] = str(elf)

    # Parallel run
    results = {}
    with ProcessPoolExecutor(
            max_workers=args.max_parallel) as pool:
        futs = {}
        for kind, sz, blk in configs:
            tag = make_tag(kind, sz, blk, args.mhz)
            if tag in elf_map:
                f = pool.submit(
                    run_one, tag, elf_map[tag],
                    args.coremark_bin, args.mhz)
                futs[f] = tag
        for f in as_completed(futs):
            r = f.result()
            if r:
                results[r[0]] = r[1]

    # Summary
    print(f"\n{'Tag':30s} {'IPC':>8s}")
    print("-" * 40)
    for kind, sz, blk in configs:
        tag = make_tag(kind, sz, blk, args.mhz)
        if tag in results:
            ipc = results[tag]["pmu"]["ipc"]
            print(f"{tag:30s} {ipc:8.4f}")
        else:
            print(f"{tag:30s} {'N/A':>8s}")


if __name__ == "__main__":
    main()
