#!/usr/bin/env python3
"""RTL sweep for RV32IM calibration.

Builds and runs coremark across iCache, dCache, and BPU
configurations for both NPC mode and SoC mode.

NPC mode (500 MHz):
  1-a: iCache [512,1k,2k,4k] x blk [16,32]  (8 configs)
  1-b: dCache [256,512,1k,2k]                (4 configs)
  1-c: default (1 config)
  1-d: BPU bimodal [128,256,512] x btb [64,128,256] (9 configs)

SoC mode (500 MHz + 1000 MHz):
  1-a: iCache [512,1k,2k,4k] x blk [16,32]  (8 configs)
  1-b: dCache [256,512,1k,2k]                (4 configs)
  1-c: default (1 config)

Serial compile, parallel run (max 3).

Usage:
  python3 scripts/rv32im_cal_sweep.py --mode npc
  python3 scripts/rv32im_cal_sweep.py --mode soc --mhz 500
  python3 scripts/rv32im_cal_sweep.py --mode soc --mhz 1000
  python3 scripts/rv32im_cal_sweep.py --mode all
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
CM_NPC_BIN = Path(os.environ.get(
    "AM_TEST", "")).parent \
    / "benchmarks" / "coremark" / "build" \
    / "coremark-riscv32im-npc.bin"
CM_SOC_BIN = Path(os.environ.get(
    "AM_TEST", "")).parent \
    / "benchmarks" / "coremark" / "build" \
    / "coremark-riscv32im-ysyxsoc.bin"

# Default cache/BPU values matching RTL Extended config
IC_DEFAULT = {"size": 1024, "blk": 16}
DC_DEFAULT = {"size": 1024, "blk": 16}
BPU_RAS = 8

# Sweep dimensions
IC_SIZES = [512, 1024, 2048, 4096]
IC_BLKS = [16, 32]
DC_SIZES = [256, 512, 1024, 2048]
DC_BLK = 16
BPU_ENTRIES = [128, 256, 512]
BTB_SIZES = [64, 128, 256]


def make_tag(mode, sweep, params, mhz):
    """Build canonical output tag."""
    prefix = f"rv32im-{mode}-{mhz}"
    if sweep == "icache":
        return (f"{prefix}/{sweep}_ic{params['ic_sz']}"
                f"_ln{params['ic_blk']}")
    elif sweep == "dcache":
        return (f"{prefix}/{sweep}_dc{params['dc_sz']}"
                f"_ln{params['dc_blk']}")
    elif sweep == "bpu":
        return (f"{prefix}/{sweep}_bp{params['bp_entries']}"
                f"_btb{params['btb_entries']}")
    elif sweep == "default":
        return f"{prefix}/default"
    return f"{prefix}/{sweep}"


def build_rtl_args(params, socmode):
    """Build RTL_SCALA_ARG string."""
    parts = ["--config-extended"]
    ic_sz = params.get("ic_sz", IC_DEFAULT["size"])
    ic_blk = params.get("ic_blk", IC_DEFAULT["blk"])
    dc_sz = params.get("dc_sz", DC_DEFAULT["size"])
    dc_blk = params.get("dc_blk", DC_DEFAULT["blk"])
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


def compile_one(tag, params, socmode, mhz):
    """Compile one RTL config. Returns (tag, elf_path) or None."""
    elf_dst = NPC_HOME / "build-sim" / "rvproc" / elf_name(tag)
    if elf_dst.exists():
        print(f"[SKIP] {tag}", flush=True)
        return (tag, str(elf_dst))

    rtl_args = build_rtl_args(params, socmode)
    socmode_flag = f"SOCMODE={'1' if socmode else '0'}"
    mhz_flag = f"MHZ={mhz}" if socmode else ""
    cmd = (
        f"make -C {NPC_HOME} compile DIFFENA=0 DPRINTF=0 "
        f"LOGENA=0 {socmode_flag} DBGENA=1 NVBENA=0 {mhz_flag} "
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


def run_one(tag, elf_path, cm_bin, mhz):
    """Run one RTL sim. Returns (tag, ipc) or None."""
    outdir = NPC_HOME / "ccout" / tag
    outdir.mkdir(parents=True, exist_ok=True)
    timeout_cyc = mhz * 500_000
    cmd = f"{elf_path} {cm_bin} -M {timeout_cyc} -R {tag}"
    print(f"[RUN] {tag}", flush=True)
    r = subprocess.run(
        cmd, shell=True, capture_output=True, text=True,
        timeout=600)
    sf = outdir / "stats.json"
    if not sf.exists():
        print(f"[FAIL] run {tag}: no stats", flush=True)
        return None
    with open(sf) as f:
        d = json.load(f)
    ipc = d["pmu"]["ipc"]
    print(f"[DONE] {tag} IPC={ipc:.4f}", flush=True)
    return (tag, ipc)


def gen_configs(mode, mhz):
    """Generate list of (tag, params, socmode) tuples."""
    socmode = (mode == "soc")
    configs = []

    # 1-a: iCache sweep
    for sz in IC_SIZES:
        for blk in IC_BLKS:
            p = {"ic_sz": sz, "ic_blk": blk, "bp_type": "none"}
            tag = make_tag(mode, "icache", p, mhz)
            configs.append((tag, p, socmode))

    # 1-b: dCache sweep
    for sz in DC_SIZES:
        p = {"dc_sz": sz, "dc_blk": DC_BLK, "bp_type": "none"}
        tag = make_tag(mode, "dcache", p, mhz)
        configs.append((tag, p, socmode))

    # 1-c: default (no BPU)
    p = {"bp_type": "none"}
    tag = make_tag(mode, "default", p, mhz)
    configs.append((tag, p, socmode))

    # 1-d: BPU sweep (NPC only)
    if mode == "npc":
        for bp_e in BPU_ENTRIES:
            for btb_e in BTB_SIZES:
                p = {
                    "bp_type": "bimodal",
                    "bp_entries": bp_e,
                    "btb_entries": btb_e,
                }
                tag = make_tag(mode, "bpu", p, mhz)
                configs.append((tag, p, socmode))

    return configs


def main():
    ap = argparse.ArgumentParser(
        description="RV32IM RTL calibration sweep")
    ap.add_argument("--mode", choices=["npc", "soc", "all"],
                    default="all")
    ap.add_argument("--mhz", type=int, default=None,
                    help="Override frequency (default: per-mode)")
    ap.add_argument("--skip-compile", action="store_true")
    ap.add_argument("--skip-run", action="store_true")
    ap.add_argument("--max-parallel", type=int, default=3)
    ap.add_argument("--npc-cm", type=str, default=str(CM_NPC_BIN))
    ap.add_argument("--soc-cm", type=str, default=str(CM_SOC_BIN))
    args = ap.parse_args()

    runs = []
    if args.mode in ("npc", "all"):
        mhz = args.mhz or 500
        cm = args.npc_cm
        runs.append(("npc", mhz, cm, gen_configs("npc", mhz)))
    if args.mode in ("soc", "all"):
        for mhz in ([args.mhz] if args.mhz else [500, 1000]):
            cm = args.soc_cm
            runs.append(("soc", mhz, cm,
                         gen_configs("soc", mhz)))

    all_results = {}

    for mode, mhz, cm_bin, configs in runs:
        print(f"\n{'='*60}")
        print(f"  {mode.upper()} mode @ {mhz} MHz "
              f"({len(configs)} configs)")
        print(f"{'='*60}\n")

        # Serial compile
        elf_map = {}
        if not args.skip_compile:
            for tag, params, socmode in configs:
                result = compile_one(tag, params, socmode, mhz)
                if result:
                    elf_map[result[0]] = result[1]
        else:
            for tag, params, socmode in configs:
                elf = (NPC_HOME / "build-sim" / "rvproc"
                       / elf_name(tag))
                if elf.exists():
                    elf_map[tag] = str(elf)

        # Parallel run
        if not args.skip_run:
            with ProcessPoolExecutor(
                    max_workers=args.max_parallel) as pool:
                futs = {}
                for tag, params, socmode in configs:
                    if tag in elf_map:
                        f = pool.submit(
                            run_one, tag, elf_map[tag],
                            cm_bin, mhz)
                        futs[f] = tag
                for f in as_completed(futs):
                    r = f.result()
                    if r:
                        all_results[r[0]] = r[1]

    # Summary
    print(f"\n{'='*60}")
    print("  SUMMARY")
    print(f"{'='*60}")
    print(f"\n{'Tag':50s} {'IPC':>8s}")
    print("-" * 60)
    for mode, mhz, cm_bin, configs in runs:
        for tag, params, socmode in configs:
            if tag in all_results:
                print(f"{tag:50s} {all_results[tag]:8.4f}")
            else:
                print(f"{tag:50s} {'N/A':>8s}")

    # Save results
    out_json = NPC_HOME / "ccout" / "rv32im_cal_results.json"
    with open(out_json, "w") as f:
        json.dump(all_results, f, indent=2)
    print(f"\nResults saved to {out_json}")


if __name__ == "__main__":
    main()
