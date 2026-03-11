#!/usr/bin/env python3
"""RTL sweep for SoC calibration (2-iter coremark, 1 GHz).

Sweep groups:
  1. BPU: NoBPU + Bimodal BHT={128,256,1024} x BTB={64,128,512}
         with large caches (2kB iCache, 2kB dCache)
  2. Pipeline: large caches (4kB each), NoBPU (isolate RAW/forwarding)
  3. Cache: iCache/dCache {512,1024,2048} x line {16,32}
         NoBPU, large counter-cache to minimize cross-noise

Serial compile, parallel run.

Usage:
  python3 scripts/soc_cal_cm2_sweep.py [--skip-compile] [--skip-run]
  python3 scripts/soc_cal_cm2_sweep.py --group bpu
  python3 scripts/soc_cal_cm2_sweep.py --group cache
  python3 scripts/soc_cal_cm2_sweep.py --group pipeline
"""

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path
from concurrent.futures import ProcessPoolExecutor, as_completed

NPC_HOME = Path(os.environ.get(
    "NPC_HOME",
    Path(__file__).resolve().parent.parent))
CM_SOC_BIN = Path(os.environ.get(
    "AM_TEST", "")).parent \
    / "benchmarks" / "coremark" / "build" \
    / "coremark-riscv32im-ysyxsoc.bin"

MHZ = 1000
OUTDIR_PREFIX = "soc-cal-cm2"

# Large caches for BPU/pipeline isolation
LARGE_IC = {"size": 4096, "blk": 16}
LARGE_DC = {"size": 2048, "blk": 16}

# Default cache for cache sweeps
DEFAULT_IC = {"size": 1024, "blk": 16}
DEFAULT_DC = {"size": 1024, "blk": 16}

# BPU sweep dimensions
BPU_BHT_ENTRIES = [128, 256, 1024]
BPU_BTB_SIZES = [64, 128, 512]
BPU_RAS = 8

# Cache sweep dimensions
IC_SIZES = [512, 1024, 2048]
DC_SIZES = [512, 1024, 2048]
CACHE_BLKS = [16, 32]


def make_tag(group, params):
    """Build canonical output tag."""
    prefix = f"{OUTDIR_PREFIX}"
    if group == "bpu":
        bp = params.get("bp_type", "none")
        if bp == "none":
            return f"{prefix}/bpu_none"
        return (f"{prefix}/bpu_h{params['bp_entries']}"
                f"_t{params['btb_entries']}")
    elif group == "pipeline":
        return f"{prefix}/pipeline_default"
    elif group == "icache":
        return (f"{prefix}/icache_ic{params['ic_sz']}"
                f"_ln{params['ic_blk']}")
    elif group == "dcache":
        return (f"{prefix}/dcache_dc{params['dc_sz']}"
                f"_ln{params['dc_blk']}")
    return f"{prefix}/{group}"


def build_rtl_args(params):
    """Build RTL_SCALA_ARG string."""
    parts = ["--config-extended"]
    ic_sz = params.get("ic_sz", DEFAULT_IC["size"])
    ic_blk = params.get("ic_blk", DEFAULT_IC["blk"])
    dc_sz = params.get("dc_sz", DEFAULT_DC["size"])
    dc_blk = params.get("dc_blk", DEFAULT_DC["blk"])
    parts += [
        f"--l1i-size {ic_sz}", f"--l1i-blksize {ic_blk}",
        f"--l1d-size {dc_sz}", f"--l1d-blksize {dc_blk}",
    ]
    bp_type = params.get("bp_type", "none")
    if bp_type == "none":
        parts.append("--bp-none")
    elif bp_type == "bimodal":
        parts.append("--bp-bimodal")
        parts.append(f"--bp-entries {params['bp_entries']}")
        parts.append(f"--btb-entries {params['btb_entries']}")
        parts.append(f"--ras-size {BPU_RAS}")
    return " ".join(parts)


def elf_name(tag):
    """Convert tag to elf filename."""
    return f"rvproc_{tag.replace('/', '_')}.elf"


def compile_one(tag, params):
    """Compile one RTL config. Returns (tag, elf_path) or None."""
    elf_dst = NPC_HOME / "build-sim" / "rvproc" / elf_name(tag)
    if elf_dst.exists():
        print(f"[SKIP] {tag}", flush=True)
        return (tag, str(elf_dst))

    rtl_args = build_rtl_args(params)
    cmd = (
        f"make -C {NPC_HOME} compile DIFFENA=0 DPRINTF=0 "
        f"LOGENA=0 SOCMODE=1 DBGENA=1 NVBENA=0 MHZ={MHZ} "
        f'RTL_SCALA_ARG="{rtl_args}"')
    print(f"[COMPILE] {tag} ...", flush=True)
    r = subprocess.run(
        cmd, shell=True, capture_output=True, text=True,
        timeout=600)
    if r.returncode != 0:
        print(f"[FAIL] compile {tag}:\n{r.stderr[-500:]}",
              flush=True)
        return None

    src = NPC_HOME / "build-sim" / "rvproc" / "rvproc.elf"
    if src.exists():
        elf_dst.parent.mkdir(parents=True, exist_ok=True)
        src.rename(elf_dst)
        print(f"[OK] {tag}", flush=True)
        return (tag, str(elf_dst))
    print(f"[FAIL] {tag}: no elf produced", flush=True)
    return None


def run_one(tag, elf_path, cm_bin):
    """Run one RTL sim. Returns (tag, stats_dict) or None."""
    outdir = NPC_HOME / "ccout" / tag
    outdir.mkdir(parents=True, exist_ok=True)
    timeout_cyc = MHZ * 500_000
    cmd = f"{elf_path} {cm_bin} -M {timeout_cyc} -R {tag}"
    print(f"[RUN] {tag}", flush=True)
    r = subprocess.run(
        cmd, shell=True, capture_output=True, text=True,
        timeout=1200)
    sf = outdir / "stats.json"
    if not sf.exists():
        print(f"[FAIL] run {tag}: no stats", flush=True)
        return None
    with open(sf) as f:
        d = json.load(f)
    ipc = d["pmu"]["ipc"]
    print(f"[DONE] {tag} IPC={ipc:.4f}", flush=True)
    return (tag, d["pmu"])


def gen_bpu_configs():
    """BPU sweep configs with large caches."""
    configs = []
    # NoBPU baseline
    p = {
        "ic_sz": LARGE_IC["size"], "ic_blk": LARGE_IC["blk"],
        "dc_sz": LARGE_DC["size"], "dc_blk": LARGE_DC["blk"],
        "bp_type": "none",
    }
    configs.append((make_tag("bpu", p), p))

    # Bimodal BHT x BTB
    for bht in BPU_BHT_ENTRIES:
        for btb in BPU_BTB_SIZES:
            p = {
                "ic_sz": LARGE_IC["size"],
                "ic_blk": LARGE_IC["blk"],
                "dc_sz": LARGE_DC["size"],
                "dc_blk": LARGE_DC["blk"],
                "bp_type": "bimodal",
                "bp_entries": bht,
                "btb_entries": btb,
            }
            configs.append((make_tag("bpu", p), p))
    return configs


def gen_pipeline_configs():
    """Pipeline RAW/forwarding config: large caches, NoBPU."""
    p = {
        "ic_sz": LARGE_IC["size"], "ic_blk": LARGE_IC["blk"],
        "dc_sz": LARGE_DC["size"], "dc_blk": LARGE_DC["blk"],
        "bp_type": "none",
    }
    return [(make_tag("pipeline", p), p)]


def gen_cache_configs():
    """iCache and dCache sweeps."""
    configs = []
    # iCache sweep (large dCache, NoBPU)
    for sz in IC_SIZES:
        for blk in CACHE_BLKS:
            p = {
                "ic_sz": sz, "ic_blk": blk,
                "dc_sz": LARGE_DC["size"],
                "dc_blk": LARGE_DC["blk"],
                "bp_type": "none",
            }
            configs.append((make_tag("icache", p), p))

    # dCache sweep (large iCache, NoBPU)
    for sz in DC_SIZES:
        for blk in CACHE_BLKS:
            p = {
                "ic_sz": LARGE_IC["size"],
                "ic_blk": LARGE_IC["blk"],
                "dc_sz": sz, "dc_blk": blk,
                "bp_type": "none",
            }
            configs.append((make_tag("dcache", p), p))
    return configs


def main():
    ap = argparse.ArgumentParser(
        description="SoC calibration RTL sweep (cm2, 1 GHz)")
    ap.add_argument("--group", choices=["bpu", "pipeline", "cache",
                                        "all"],
                    default="all")
    ap.add_argument("--skip-compile", action="store_true")
    ap.add_argument("--skip-run", action="store_true")
    ap.add_argument("--max-parallel", type=int, default=3)
    ap.add_argument("--cm-bin", type=str, default=str(CM_SOC_BIN))
    args = ap.parse_args()

    configs = []
    if args.group in ("bpu", "all"):
        configs += gen_bpu_configs()
    if args.group in ("pipeline", "all"):
        configs += gen_pipeline_configs()
    if args.group in ("cache", "all"):
        configs += gen_cache_configs()

    print(f"\n{'='*60}")
    print(f"  SoC calibration sweep: {len(configs)} configs @ "
          f"{MHZ} MHz")
    print(f"{'='*60}\n")

    # Serial compile
    elf_map = {}
    if not args.skip_compile:
        for tag, params in configs:
            result = compile_one(tag, params)
            if result:
                elf_map[result[0]] = result[1]
    else:
        for tag, params in configs:
            elf = (NPC_HOME / "build-sim" / "rvproc"
                   / elf_name(tag))
            if elf.exists():
                elf_map[tag] = str(elf)

    # Parallel run
    all_results = {}
    if not args.skip_run:
        with ProcessPoolExecutor(
                max_workers=args.max_parallel) as pool:
            futs = {}
            for tag, params in configs:
                if tag in elf_map:
                    f = pool.submit(
                        run_one, tag, elf_map[tag], args.cm_bin)
                    futs[f] = tag
            for f in as_completed(futs):
                r = f.result()
                if r:
                    all_results[r[0]] = r[1]

    # Summary
    print(f"\n{'='*60}")
    print("  SUMMARY")
    print(f"{'='*60}")
    print(f"\n{'Tag':55s} {'IPC':>8s}")
    print("-" * 65)
    for tag, _ in configs:
        if tag in all_results:
            ipc = all_results[tag]["ipc"]
            print(f"{tag:55s} {ipc:8.4f}")
        else:
            print(f"{tag:55s} {'N/A':>8s}")

    # Save results
    outdir = NPC_HOME / "ccout" / OUTDIR_PREFIX
    outdir.mkdir(parents=True, exist_ok=True)
    out_json = outdir / "sweep_results.json"
    with open(out_json, "w") as f:
        json.dump(all_results, f, indent=2)
    print(f"\nResults saved to {out_json}")


if __name__ == "__main__":
    main()
