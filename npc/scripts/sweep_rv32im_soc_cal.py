#!/usr/bin/env python3
"""sweep_rv32im_soc_cal.py -- RTL sweep for RV32IM SoC calibration.

Builds and runs coremark-riscv32im-ysyxsoc across BPU and cache configs
for npsim calibration.  Serial compile, parallel run.

Sweep groups:
  bpu:   NoBPU + Bimodal BTB={64,128,512} x BHT={128,256,1024}
         (fixed iCache 1kB/16B, dCache 1kB/16B)
  cache: iCache {512,1024,2048} x blk {16,32}
         dCache {512,1024} x blk 16
         (fixed BPU bimodal h256-t128, matching GlbCtrl defaults)
  pipe:  large caches (4kB/16B) + NoBPU (for RAW/forwarding validation)

Usage:
  python3 scripts/sweep_rv32im_soc_cal.py --mhz 1000 --group all
  python3 scripts/sweep_rv32im_soc_cal.py --mhz 500 --group bpu --skip-compile
  python3 scripts/sweep_rv32im_soc_cal.py --mhz 1000 --mhz 500 --group cache
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
    / "coremark-riscv32im-ysyxsoc.bin"

MAX_CYCLES = 500_000_000
IC_ASSOC = 1
DC_ASSOC = 1
DC_BLK = 16


def make_prefix(mhz):
    return f"rv32im_soc_cal_{mhz}MHz"


def canonical_tag(mhz, ic_sz, ic_blk, dc_sz, bpu_type,
                  bht=0, btb=0):
    prefix = make_prefix(mhz)
    s = f"{prefix}_l1i-{ic_sz}-b{ic_blk}-a{IC_ASSOC}" \
        f"_l1d-{dc_sz}-b{DC_BLK}-a{DC_ASSOC}"
    if bpu_type == "none":
        s += "_bpu-none"
    else:
        s += f"_bpu-bimodal-h{bht}-t{btb}"
    return s


def elf_name(tag):
    return f"rvproc_{tag}.elf"


def build_rtl_args(ic_sz, ic_blk, dc_sz, bpu_type,
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


def compile_one(tag, ic_sz, ic_blk, dc_sz, bpu_type,
                bht=0, btb=0, mhz=1000):
    elf_dst = NPC_HOME / "build-sim" / "rvproc" / elf_name(tag)
    if elf_dst.exists():
        print(f"[SKIP] {tag} (elf exists)")
        return (tag, str(elf_dst))

    rtl_args = build_rtl_args(ic_sz, ic_blk, dc_sz, bpu_type,
                              bht, btb)
    cmd = (
        f"make -C {NPC_HOME} compile DIFFENA=0 DPRINTF=0 "
        f"LOGENA=0 SOCMODE=1 DBGENA=1 NVBENA=0 MHZ={mhz} "
        f'RTL_SCALA_ARG="{rtl_args}"')
    print(f"[COMPILE] {tag} ...", flush=True)
    r = subprocess.run(
        cmd, shell=True, capture_output=True, text=True,
        timeout=900)
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


def run_one(tag, elf_path, cm_bin, prefix):
    outdir = NPC_HOME / "ccout" / prefix / tag
    outdir.mkdir(parents=True, exist_ok=True)
    cmd = f"{elf_path} {cm_bin} -M {MAX_CYCLES} -R {prefix}/{tag}"
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
    return (tag, d)


def gen_bpu_configs(mhz):
    """NoBPU + Bimodal BPU sweep (fixed 1kB caches)."""
    ic_sz, ic_blk, dc_sz = 1024, 16, 1024
    configs = []
    # NoBPU
    tag = canonical_tag(mhz, ic_sz, ic_blk, dc_sz, "none")
    configs.append((tag, ic_sz, ic_blk, dc_sz, "none", 0, 0))
    # Bimodal sweep
    for bht in [128, 256, 1024]:
        for btb in [64, 128, 512]:
            tag = canonical_tag(mhz, ic_sz, ic_blk, dc_sz,
                                "bimodal", bht, btb)
            configs.append((tag, ic_sz, ic_blk, dc_sz, "bimodal",
                            bht, btb))
    return configs


def gen_cache_configs(mhz):
    """iCache x dCache sweep (fixed bimodal h256-t128)."""
    bht, btb = 256, 128
    configs = []
    for ic_sz in [512, 1024, 2048]:
        for ic_blk in [16, 32]:
            for dc_sz in [512, 1024]:
                tag = canonical_tag(mhz, ic_sz, ic_blk, dc_sz,
                                    "bimodal", bht, btb)
                configs.append((tag, ic_sz, ic_blk, dc_sz,
                                "bimodal", bht, btb))
    return configs


def gen_pipe_configs(mhz):
    """Large cache + NoBPU for pipeline validation."""
    ic_sz, ic_blk, dc_sz = 4096, 16, 2048
    tag = canonical_tag(mhz, ic_sz, ic_blk, dc_sz, "none")
    return [(tag, ic_sz, ic_blk, dc_sz, "none", 0, 0)]


def main():
    ap = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--group", choices=["bpu", "cache", "pipe", "all"],
                    default="all")
    ap.add_argument("--mhz", type=int, nargs="+", default=[500, 1000],
                    help="Frequency(s) to sweep (default: 500 1000)")
    ap.add_argument("--skip-compile", action="store_true")
    ap.add_argument("--skip-run", action="store_true")
    ap.add_argument("--max-parallel", type=int, default=3)
    ap.add_argument("--coremark-bin", type=str,
                    default=str(COREMARK_BIN))
    args = ap.parse_args()

    for mhz in args.mhz:
        prefix = make_prefix(mhz)
        configs = []
        if args.group in ("bpu", "all"):
            configs += gen_bpu_configs(mhz)
        if args.group in ("cache", "all"):
            configs += gen_cache_configs(mhz)
        if args.group in ("pipe", "all"):
            configs += gen_pipe_configs(mhz)

        # Deduplicate by tag
        seen = set()
        unique = []
        for c in configs:
            if c[0] not in seen:
                seen.add(c[0])
                unique.append(c)
        configs = unique

        print(f"\n{'='*60}")
        print(f"  {prefix}: {len(configs)} configs @ {mhz} MHz")
        print(f"{'='*60}\n")

        # Serial compile
        elf_map = {}
        if not args.skip_compile:
            for tag, ic_sz, ic_blk, dc_sz, bp, bht, btb in configs:
                result = compile_one(tag, ic_sz, ic_blk, dc_sz, bp,
                                     bht, btb, mhz)
                if result:
                    elf_map[result[0]] = result[1]
        else:
            for tag, *_ in configs:
                elf = (NPC_HOME / "build-sim" / "rvproc"
                       / elf_name(tag))
                if elf.exists():
                    elf_map[tag] = str(elf)
                else:
                    print(f"[WARN] No elf for {tag}")

        if args.skip_run:
            print(f"Skip run. {len(elf_map)} elfs ready.")
            continue

        # Parallel run
        results = {}
        with ProcessPoolExecutor(
                max_workers=args.max_parallel) as pool:
            futs = {}
            for tag, *_ in configs:
                if tag in elf_map:
                    f = pool.submit(run_one, tag, elf_map[tag],
                                    args.coremark_bin, prefix)
                    futs[f] = tag
            for f in as_completed(futs):
                r = f.result()
                if r:
                    results[r[0]] = r[1]

        # Summary
        print(f"\n{'Config':70s} {'IPC':>8s}")
        print("-" * 80)
        for tag, *_ in configs:
            if tag in results:
                ipc = results[tag]["pmu"]["ipc"]
                print(f"{tag:70s} {ipc:8.4f}")
            else:
                print(f"{tag:70s} {'N/A':>8s}")


if __name__ == "__main__":
    main()
