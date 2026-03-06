#!/usr/bin/env python3
"""npSim BPU calibration sweep: run matching configs and compare to RTL.

Usage:
  python3 scripts/bpu_npsim_sweep.py \
    --rtl-results /path/to/rtl/results.json \
    --outdir bpu_cali_results
"""

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

NPSIM_HOME = Path(os.environ.get(
    "NPSIM_HOME",
    Path(__file__).resolve().parent.parent.parent / "npsim"))
NPSIM_BIN = NPSIM_HOME / "build" / "npsim.elf"
TRACE = NPSIM_HOME / "tests" / "coremark-soc-cal.nptr.zst"

SOC_DEFAULTS = {
    "sdram-lat-us": "0.047", "axi-ovhd-cyc": "4",
    "sdram-burst-us": "0.024",
    "sram-lat": "1",
    "ifq": "3",
    "br-pen": "1",
    "l1i-size": "1024B",
    "l1i-blksize": "16",
    "l1i-assoc": "1",
}


def run_npsim(cfg, outdir_tag):
    bp_type = cfg["bp_type"]
    btb_size = cfg["btb_size"]
    ras_size = cfg["ras_size"]

    cmd = [
        str(NPSIM_BIN), str(TRACE),
        "--bpu-type", bp_type,
        "--bpu-size", str(btb_size),
        "--btb-size", str(btb_size),
        "--ras-size", str(ras_size),
        "--outdir", outdir_tag,
        "--print-none",
    ]
    for k, v in SOC_DEFAULTS.items():
        cmd += [f"--{k}", v]

    r = subprocess.run(cmd, capture_output=True, text=True, cwd=str(NPSIM_HOME))
    if r.returncode != 0:
        print(f"  FAILED: {r.stderr[:200]}", flush=True)
        return None

    stats_path = NPSIM_HOME / "simout" / outdir_tag / "stats.json"
    if not stats_path.exists():
        return None
    with open(stats_path) as f:
        stats = json.load(f)

    s1 = stats.get("stats1", {})
    core = s1.get("Core", {})
    bpu = s1.get("BranchUnit", {})
    return {
        "ipc": core.get("ipc", 0),
        "cycles": core.get("cycles", 0),
        "insts": core.get("insts", 0),
        "bp_accesses": bpu.get("accesses", 0),
        "bp_misses": bpu.get("misses", 0),
        "bp_miss_rate": bpu.get("miss_rate", 0),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rtl-results", required=True)
    ap.add_argument("--outdir", default="bpu_cali_results")
    args = ap.parse_args()

    outdir = Path(args.outdir)
    outdir.mkdir(parents=True, exist_ok=True)

    with open(args.rtl_results) as f:
        rtl_data = json.load(f)

    results = []
    for rtl in rtl_data:
        tag = rtl["tag"]
        cfg = {
            "bp_type": rtl["bp_type"],
            "btb_size": rtl["btb_size"],
            "ras_size": rtl["ras_size"],
        }
        print(f"[{tag}] Running npSim...", flush=True)
        npsim_tag = f"bpu_cali/{tag}"
        sim = run_npsim(cfg, npsim_tag)
        if sim is None:
            print(f"[{tag}] FAILED", flush=True)
            continue

        rtl_ipc = rtl.get("ipc", 0)
        sim_ipc = sim["ipc"]
        ipc_err = abs(sim_ipc - rtl_ipc) / rtl_ipc * 100 if rtl_ipc > 0 else 0

        rtl_miss = rtl.get("bp_misses", 0)
        sim_miss = sim["bp_misses"]
        miss_err = abs(sim_miss - rtl_miss) / rtl_miss * 100 if rtl_miss > 0 else 0

        entry = {
            "tag": tag,
            **cfg,
            "rtl_ipc": rtl_ipc,
            "sim_ipc": sim_ipc,
            "ipc_err_pct": round(ipc_err, 2),
            "rtl_bp_misses": rtl_miss,
            "sim_bp_misses": sim_miss,
            "miss_err_pct": round(miss_err, 2),
            "rtl_bp_accesses": rtl.get("bp_accesses", 0),
            "sim_bp_accesses": sim["bp_accesses"],
        }
        results.append(entry)

        print(f"[{tag}] RTL IPC={rtl_ipc:.6f} SIM IPC={sim_ipc:.6f} "
              f"err={ipc_err:.2f}%  miss_err={miss_err:.1f}%", flush=True)

        with open(outdir / "cali_results.json", "w") as f:
            json.dump(results, f, indent=2)

    print(f"\nDone. Max IPC error: "
          f"{max(r['ipc_err_pct'] for r in results):.2f}%")


if __name__ == "__main__":
    main()
