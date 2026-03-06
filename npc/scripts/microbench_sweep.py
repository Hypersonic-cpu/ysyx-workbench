#!/usr/bin/env python3
"""Microbench sweep: run npSim with parameter sweeps, compare.

Step 1: Collect trace (if not already available):
  cd $NEMU_HOME && make menuconfig
    # Enable NPSIM_TRACE, SOC mode, disable ITRACE
  cd $AM_TEST/../benchmarks/microbench && \
    make ARCH=riscv32e-nemu mainargs=train run
  cp $NEMU_HOME/build/npsim.nptr.zst \
    $NPSIM_HOME/tests/microbench-soc-ext.nptr.zst

Step 2: Run this script:
  python3 scripts/microbench_sweep.py \
    --trace $NPSIM_HOME/tests/microbench-soc-ext.nptr.zst \
    --sweep icache --freq-mhz 1000

Step 3: Results are saved to npc/ccout/mb-sweep-<name>/
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
NPSIM_HOME = Path(os.environ.get(
    "NPSIM_HOME",
    Path(__file__).resolve().parent.parent.parent / "npsim"))
NPSIM_BIN = NPSIM_HOME / "build" / "npsim.elf"

# ── Default extended config (bimodal BPU + 1kB caches) ────────
DEFAULTS = {
    "bpu_type": "bimodal", "bpu_size": 256,
    "btb_size": 128, "ras_size": 8,
    "l1i_size": 1024, "l1i_blksize": 16,
    "l1d_size": 1024, "l1d_blksize": 16,
    "sdram_lat_us": 0.047, "sdram_burst_us": 0.024,
    "axi_ovhd_cyc": 4, "sram_lat": 1,
    "mmio_lat": 3, "br_pen": 5, "ifq": 3,
    "freq_mhz": 1000,
}


def run_npsim(trace, cfg, tag):
    """Run npSim with given config dict, return stats dict."""
    cmd = [
        str(NPSIM_BIN), str(trace),
        "--bpu-type", str(cfg["bpu_type"]),
        "--bpu-size", str(cfg["bpu_size"]),
        "--btb-size", str(cfg["btb_size"]),
        "--ras-size", str(cfg["ras_size"]),
        "--l1i-size", str(cfg["l1i_size"]),
        "--l1i-blksize", str(cfg["l1i_blksize"]),
        "--l1d-size", str(cfg["l1d_size"]),
        "--l1d-blksize", str(cfg["l1d_blksize"]),
        "--sdram-lat-us", str(cfg["sdram_lat_us"]),
        "--sdram-burst-us", str(cfg["sdram_burst_us"]),
        "--axi-ovhd-cyc", str(cfg["axi_ovhd_cyc"]),
        "--sram-lat", str(cfg["sram_lat"]),
        "--mmio-lat", str(cfg["mmio_lat"]),
        "--br-pen", str(cfg["br_pen"]),
        "--ifq", str(cfg["ifq"]),
        "--freq-mhz", str(cfg["freq_mhz"]),
        "--outdir", tag,
        "--print-none",
    ]
    r = subprocess.run(
        cmd, capture_output=True, text=True,
        cwd=str(NPSIM_HOME), timeout=120)
    if r.returncode != 0:
        print(f"[FAIL] {tag}: {r.stderr[:200]}", flush=True)
        return None
    sf = NPSIM_HOME / "simout" / tag / "stats.json"
    if not sf.exists():
        return None
    with open(sf) as f:
        return json.load(f)


def gen_icache_sweep(base):
    """Generate iCache sweep configs."""
    configs = []
    for sz in [256, 512, 1024]:
        for blk in [16, 32]:
            c = dict(base)
            c["l1i_size"] = sz
            c["l1i_blksize"] = blk
            configs.append(
                (f"ic{sz}_ln{blk}", c))
    return configs


def gen_dcache_sweep(base):
    """Generate dCache sweep configs."""
    configs = []
    for sz in [256, 512, 1024]:
        for blk in [16, 32]:
            c = dict(base)
            c["l1d_size"] = sz
            c["l1d_blksize"] = blk
            configs.append(
                (f"dc{sz}_ln{blk}", c))
    return configs


def gen_bpu_sweep(base):
    """Generate BPU sweep configs."""
    configs = []
    for btb in [64, 128, 256]:
        for bp in [64, 128, 256]:
            for ras in [4, 8]:
                c = dict(base)
                c["btb_size"] = btb
                c["bpu_size"] = bp
                c["ras_size"] = ras
                configs.append(
                    (f"btb{btb}_bp{bp}_r{ras}", c))
    return configs


def gen_freq_sweep(base):
    """Generate frequency sweep configs."""
    configs = []
    for freq in [200, 500, 1000]:
        c = dict(base)
        c["freq_mhz"] = freq
        configs.append((f"f{freq}", c))
    return configs


SWEEPS = {
    "icache": gen_icache_sweep,
    "dcache": gen_dcache_sweep,
    "bpu": gen_bpu_sweep,
    "freq": gen_freq_sweep,
}


def main():
    ap = argparse.ArgumentParser(
        description="npSim microbench parameter sweep")
    ap.add_argument(
        "--trace", type=str, required=True,
        help="Path to .nptr.zst trace file")
    ap.add_argument(
        "--sweep", type=str, required=True,
        choices=list(SWEEPS.keys()),
        help="Which parameter to sweep")
    ap.add_argument(
        "--freq-mhz", type=int, default=1000,
        help="Core frequency (default: 1000)")
    ap.add_argument(
        "--max-parallel", type=int, default=3,
        help="Max parallel npSim runs")
    ap.add_argument(
        "--out-name", type=str, default=None,
        help="Output dir name (default: mb-sweep-<sweep>)")
    ap.add_argument(
        "--rtl-dir", type=str, default=None,
        help="RTL ccout dir for comparison (optional)")
    args = ap.parse_args()

    base = dict(DEFAULTS)
    base["freq_mhz"] = args.freq_mhz
    sweep_fn = SWEEPS[args.sweep]
    configs = sweep_fn(base)

    if len(configs) > 64:
        print(f"WARNING: {len(configs)} configs > 64 limit!"
              f" Trimming to 64.")
        configs = configs[:64]

    out_name = args.out_name or f"mb-sweep-{args.sweep}"
    out_base = f"{out_name}"

    print(f"Running {len(configs)} configs "
          f"(max {args.max_parallel} parallel)...")

    results = {}
    with ProcessPoolExecutor(
            max_workers=args.max_parallel) as pool:
        futs = {}
        for tag, cfg in configs:
            full_tag = f"{out_base}/{tag}"
            f = pool.submit(
                run_npsim, args.trace, cfg, full_tag)
            futs[f] = tag
        for f in as_completed(futs):
            tag = futs[f]
            data = f.result()
            if data:
                core = data["stats0"]["Core"]
                results[tag] = {
                    "ipc": core["ipc"],
                    "cycles": core["cycles"],
                    "insts": core["insts"],
                    **core["CycBreakdown"],
                }
                print(f"  {tag:25s} IPC={core['ipc']:.4f}"
                      f"  cyc={core['cycles']}", flush=True)

    # Save results
    outdir = NPC_HOME / "ccout" / out_name
    outdir.mkdir(parents=True, exist_ok=True)
    with open(outdir / "results.json", "w") as f:
        json.dump(results, f, indent=2)

    # Print summary table
    print(f"\n{'Tag':25s} {'IPC':>8s} {'NoInst%':>8s}"
          f" {'LsuStl%':>8s} {'BrMis%':>8s} {'RAW%':>8s}")
    print("-" * 65)
    for tag, cfg in configs:
        if tag in results:
            r = results[tag]
            print(f"{tag:25s} {r['ipc']:8.4f}"
                  f" {r.get('NoInst_pct', 0):7.1f}%"
                  f" {r.get('LsuStall_pct', 0):7.1f}%"
                  f" {r.get('BranchMispred_pct', 0):7.1f}%"
                  f" {r.get('RAW_pct', 0):7.1f}%")

    # If RTL dir provided, compare
    if args.rtl_dir:
        rtl_base = Path(args.rtl_dir)
        print(f"\n{'Tag':25s} {'RTL_IPC':>8s} "
              f"{'npS_IPC':>8s} {'Error':>8s}")
        print("-" * 55)
        for tag, cfg in configs:
            if tag in results:
                rtl_sf = rtl_base / tag / "stats.json"
                if rtl_sf.exists():
                    with open(rtl_sf) as f:
                        rd = json.load(f)
                    rtl_ipc = rd["pmu"]["ipc"]
                    err = (results[tag]["ipc"] - rtl_ipc) \
                        / rtl_ipc * 100
                    print(f"{tag:25s} {rtl_ipc:8.4f}"
                          f" {results[tag]['ipc']:8.4f}"
                          f" {err:+7.1f}%")

    print(f"\nResults saved to {outdir}/results.json")


if __name__ == "__main__":
    main()
