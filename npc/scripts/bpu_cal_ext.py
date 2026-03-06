#!/usr/bin/env python3
"""BPU calibration: sweep RTL + npSim, compare.

Phase B-1: vary BPU params with fixed extended cache config.
Serial compile (RTL) → parallel sim (max 3) → npSim sweep → diff.
"""

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from concurrent.futures import ProcessPoolExecutor, as_completed

NPC_HOME = Path(os.environ.get("NPC_HOME",
    Path(__file__).resolve().parent.parent))
NPSIM_HOME = Path(os.environ.get("NPSIM_HOME",
    Path(__file__).resolve().parent.parent.parent / "npsim"))
NPSIM_BIN = NPSIM_HOME / "build" / "npsim.elf"
TRACE = NPSIM_HOME / "tests" / "coremark-soc-ext.nptr.zst"
COREMARK_BIN = Path(os.environ.get("AM_TEST", "")).parent / \
    "benchmarks" / "coremark" / "build" / "coremark-riscv32e-ysyxsoc.bin"

# BPU sweep configs
BTB_SIZES = [32, 64, 128, 256]
BP_ENTRIES = [64, 128, 256]
RAS_SIZES = [0, 4, 8]

CONFIGS = []
for btb in BTB_SIZES:
    for bp in BP_ENTRIES:
        for ras in RAS_SIZES:
            CONFIGS.append({"btb": btb, "bp": bp, "ras": ras})


def tag(cfg):
    return f"bm_btb{cfg['btb']}_bp{cfg['bp']}_ras{cfg['ras']}"


# ──── RTL ────
def rtl_build(cfg):
    t = tag(cfg)
    elf = NPC_HOME / "build-sim" / "rvproc" / f"rvproc_bpu_{t}.elf"
    rtl_args = (f"--config-extended --bp-bimodal "
                f"--bp-entries {cfg['bp']} --btb-entries {cfg['btb']} "
                f"--ras-size {cfg['ras']}")
    cmd = (f"make -C {NPC_HOME} compile DIFFENA=0 DPRINTF=0 LOGENA=0 "
           f"SOCMODE=1 DBGENA=1 NVBENA=0 "
           f"RTL_SCALA_ARG=\"{rtl_args}\"")
    print(f"[RTL BUILD] {t} ...", flush=True)
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if r.returncode != 0:
        print(f"[RTL BUILD] {t} FAILED", flush=True)
        return None
    # Rename binary
    src = NPC_HOME / "build-sim" / "rvproc" / "rvproc.elf"
    if src.exists():
        src.rename(elf)
        print(f"[RTL BUILD] {t} OK → {elf.name}", flush=True)
        return str(elf)
    return None


def rtl_run(cfg, elf_path, cm_bin):
    t = tag(cfg)
    out = f"bpu-cal/{t}"
    outdir = NPC_HOME / "ccout" / out
    outdir.mkdir(parents=True, exist_ok=True)
    cmd = f"{elf_path} {cm_bin} -M 500000000 -R {out}"
    print(f"[RTL RUN ] {t}", flush=True)
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True,
                       timeout=300)
    # Try JSON stats first
    stats_path = outdir / "stats.json"
    if stats_path.exists():
        result = parse_rtl_json(stats_path, cfg)
    else:
        result = parse_rtl_output(r.stdout + r.stderr, cfg)
    return result


def parse_rtl_json(stats_path, cfg):
    """Parse RTL JSON stats file (more reliable than stdout)."""
    with open(stats_path) as f:
        data = json.load(f)
    pmu = data.get("pmu", {})
    result = {"tag": tag(cfg), **cfg}
    bc = pmu.get("BlockedCause", {})
    result["cycles"] = bc.get("samples", 0)
    result["insts"] = bc.get("NoStall", 0)
    if result["cycles"] > 0:
        result["ipc"] = result["insts"] / result["cycles"]
    bp = pmu.get("BranchPred", {})
    result["bp_total"] = bp.get("samples", 0)
    result["bp_correct"] = bp.get("Correct", 0)
    result["btb_miss"] = bp.get("BtbMiss", 0)
    result["wrong_dir"] = bp.get("WrongDir", 0)
    result["wrong_tgt"] = bp.get("WrongTgt", 0)
    ic = pmu.get("L1ICache", {})
    result["icache_hit"] = ic.get("Hit", 0)
    result["icache_miss"] = ic.get("Miss", 0)
    bc_detail = pmu.get("BlockedCause", {})
    result["noinst"] = bc_detail.get("NoInst", 0)
    result["lsu_stall"] = bc_detail.get("LsuStall", 0)
    result["br_mispred"] = bc_detail.get("BranchMispred", 0)
    result["raw_stall"] = bc_detail.get("RAW", 0)
    return result


def parse_rtl_output(output, cfg):
    result = {"tag": tag(cfg), **cfg}
    m = re.search(r"Cycles\s+(\d+)", output)
    if m: result["cycles"] = int(m.group(1))
    m = re.search(r"InstRet\s+(\d+)\s+IPC\s+([\d.]+)", output)
    if m:
        result["insts"] = int(m.group(1))
        result["ipc"] = float(m.group(2))
    m = re.search(r"BranchPred\s*:\s*samples\s+(\d+)", output)
    if m: result["bp_total"] = int(m.group(1))
    m = re.search(r"Correct\s+(\d+)", output)
    if m: result["bp_correct"] = int(m.group(1))
    m = re.search(r"BtbMiss\s+(\d+)", output)
    if m: result["btb_miss"] = int(m.group(1))
    m = re.search(r"WrongDir\s+(\d+)", output)
    if m: result["wrong_dir"] = int(m.group(1))
    m = re.search(r"WrongTgt\s+(\d+)", output)
    if m: result["wrong_tgt"] = int(m.group(1))
    m = re.search(r"CoreMark PASS\s+(\d+)\s+Marks", output)
    if m: result["coremark"] = int(m.group(1))
    # iCache stats
    m = re.search(r"iCache\s*:\s*Hit\s+(\d+)\s+Miss\s+(\d+)", output)
    if m:
        result["icache_hit"] = int(m.group(1))
        result["icache_miss"] = int(m.group(2))
    return result


# ──── npSim ────
def npsim_run(cfg, outdir_base):
    t = tag(cfg)
    od = f"{outdir_base}/{t}"
    # npSim: bpu-size = bp entries (BHT), btb-size = BTB entries
    cmd = [
        str(NPSIM_BIN), str(TRACE),
        "--bpu-type", "bimodal",
        "--bpu-size", str(cfg["bp"]),
        "--btb-size", str(cfg["btb"]),
        "--ras-size", str(cfg["ras"]),
        "--l1i-size", "1024",
        "--l1i-blksize", "16",
        "--l1d-size", "1024",
        "--l1d-blksize", "16",
        "--sdram-lat-us", "0.047", "--axi-ovhd-cyc", "4",
        "--sdram-burst-us", "0.024",
        "--sram-lat", "1",
        "--ifq", "3",
        "--br-pen", "5",
        "--outdir", od,
        "--print-none",
    ]
    r = subprocess.run(cmd, capture_output=True, text=True,
                       cwd=str(NPSIM_HOME))
    if r.returncode != 0:
        print(f"[npSim] {t} FAILED: {r.stderr[:200]}", flush=True)
        return None

    stats_path = NPSIM_HOME / "simout" / od / "stats.json"
    if not stats_path.exists():
        return None
    with open(stats_path) as f:
        stats = json.load(f)

    s1 = stats.get("stats1", {})
    core = s1.get("Core", {})
    bpu = s1.get("BranchUnit", {})
    btb = s1.get("BTB", bpu)
    ic = s1.get("iCache", {})
    dc = s1.get("dCache", {})
    return {
        "tag": t, **cfg,
        "ipc": core.get("ipc", 0),
        "cycles": core.get("cycles", 0),
        "insts": core.get("insts", 0),
        "bp_total": bpu.get("accesses", 0),
        "bp_misses": bpu.get("misses", 0),
        "bp_miss_rate": bpu.get("miss_rate", 0),
        "br_accesses": bpu.get("br_accesses", 0),
        "nonbr_mispred": bpu.get("nonbr_mispred", 0),
        "miss_no_target": bpu.get("miss_no_target", 0),
        "miss_bad_pred": bpu.get("miss_bad_pred", 0),
        "miss_bad_target": bpu.get("miss_bad_target", 0),
        "icache_hit": ic.get("hits", 0),
        "icache_miss": ic.get("misses", 0),
        "dcache_hit": dc.get("hits", 0),
        "dcache_miss": dc.get("misses", 0),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--outdir", default="bpu_cal_ext")
    ap.add_argument("--coremark-bin", default=str(COREMARK_BIN))
    ap.add_argument("--skip-rtl-build", action="store_true")
    ap.add_argument("--skip-rtl-run", action="store_true")
    ap.add_argument("--skip-npsim", action="store_true")
    ap.add_argument("--max-parallel", type=int, default=3)
    args = ap.parse_args()

    outdir = Path(args.outdir)
    outdir.mkdir(parents=True, exist_ok=True)

    # Phase 1: Serial RTL builds
    elf_map = {}
    if not args.skip_rtl_build:
        for cfg in CONFIGS:
            t = tag(cfg)
            elf = rtl_build(cfg)
            if elf:
                elf_map[t] = elf
        with open(outdir / "elf_map.json", "w") as f:
            json.dump(elf_map, f, indent=2)
    else:
        elf_path = outdir / "elf_map.json"
        if elf_path.exists():
            with open(elf_path) as f:
                elf_map = json.load(f)

    # Phase 2: Parallel RTL runs
    rtl_results = []
    if not args.skip_rtl_run:
        with ProcessPoolExecutor(max_workers=args.max_parallel) as pool:
            futures = {}
            for cfg in CONFIGS:
                t = tag(cfg)
                if t in elf_map:
                    f = pool.submit(rtl_run, cfg, elf_map[t],
                                    args.coremark_bin)
                    futures[f] = t
            for f in as_completed(futures):
                r = f.result()
                if r:
                    rtl_results.append(r)
                    print(f"[RTL DONE] {r['tag']} IPC={r.get('ipc','?')}"
                          f" BP_acc={r.get('bp_correct',0)}/{r.get('bp_total',0)}"
                          , flush=True)
        with open(outdir / "rtl_results.json", "w") as f:
            json.dump(rtl_results, f, indent=2)
    else:
        rp = outdir / "rtl_results.json"
        if rp.exists():
            with open(rp) as f:
                rtl_results = json.load(f)

    # Phase 3: npSim sweep (parallel)
    npsim_results = []
    if not args.skip_npsim:
        with ProcessPoolExecutor(max_workers=args.max_parallel) as pool:
            futures = {}
            for cfg in CONFIGS:
                f = pool.submit(npsim_run, cfg, "bpu-cal-npsim2")
                futures[f] = tag(cfg)
            for f in as_completed(futures):
                r = f.result()
                if r:
                    npsim_results.append(r)
        with open(outdir / "npsim_results.json", "w") as f:
            json.dump(npsim_results, f, indent=2)
    else:
        np = outdir / "npsim_results.json"
        if np.exists():
            with open(np) as f:
                npsim_results = json.load(f)

    # Phase 4: Compare
    rtl_by_tag = {r["tag"]: r for r in rtl_results}
    npsim_by_tag = {r["tag"]: r for r in npsim_results}

    # Header
    hdr = (f"{'Tag':35s} {'RTL_IPC':>8s} {'npS_IPC':>8s} {'Err%':>6s}"
           f" {'RTL_miss':>9s} {'npS_miss':>9s}"
           f" {'npS_nbr':>8s}")
    print(f"\n{hdr}")
    print("-" * len(hdr))
    for cfg in CONFIGS:
        t = tag(cfg)
        rtl = rtl_by_tag.get(t, {})
        nps = npsim_by_tag.get(t, {})
        r_ipc = rtl.get("ipc", 0)
        n_ipc = nps.get("ipc", 0)
        err = (n_ipc - r_ipc) / r_ipc * 100 if r_ipc > 0 else 0
        r_miss = (rtl.get("bp_total", 0) - rtl.get("bp_correct", 0))
        n_miss_br = nps.get("bp_misses", 0) - nps.get("nonbr_mispred", 0)
        n_nbr = nps.get("nonbr_mispred", 0)
        print(f"{t:35s} {r_ipc:8.4f} {n_ipc:8.4f} {err:+5.1f}%"
              f" {r_miss:9d} {n_miss_br:9d}"
              f" {n_nbr:8d}")


if __name__ == "__main__":
    main()
