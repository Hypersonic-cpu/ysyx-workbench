#!/usr/bin/env python3
"""dCache calibration: sweep RTL + npSim, compare.

Phase B-2: vary dCache size/line with fixed BPU (btb128/bp256/ras8).
Serial compile (RTL) → parallel sim (max 3) → npSim sweep → diff.
"""

import argparse
import json
import os
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

# Fixed BPU config
BPU = {"btb": 128, "bp": 256, "ras": 8}

# dCache sweep: size × line
DC_SIZES = [256, 512, 1024]
DC_LINES = [16, 32]

CONFIGS = []
for sz in DC_SIZES:
    for ln in DC_LINES:
        CONFIGS.append({"dc_size": sz, "dc_line": ln})


def tag(cfg):
    return f"dc{cfg['dc_size']}_ln{cfg['dc_line']}"


# ──── RTL ────
def rtl_build(cfg):
    t = tag(cfg)
    elf = NPC_HOME / "build-sim" / "rvproc" / f"rvproc_dc_{t}.elf"
    rtl_args = (f"--config-extended --bp-bimodal "
                f"--bp-entries {BPU['bp']} --btb-entries {BPU['btb']} "
                f"--ras-size {BPU['ras']} "
                f"--l1d-size {cfg['dc_size']} "
                f"--l1d-blksize {cfg['dc_line']}")
    cmd = (f"make -C {NPC_HOME} compile DIFFENA=0 DPRINTF=0 LOGENA=0 "
           f"SOCMODE=1 DBGENA=1 NVBENA=0 "
           f"RTL_SCALA_ARG=\"{rtl_args}\"")
    print(f"[RTL BUILD] {t} ...", flush=True)
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True,
                       timeout=300)
    if r.returncode != 0:
        print(f"[RTL BUILD] {t} FAILED:\n{r.stderr[-500:]}", flush=True)
        return None
    src = NPC_HOME / "build-sim" / "rvproc" / "rvproc.elf"
    if src.exists():
        src.rename(elf)
        print(f"[RTL BUILD] {t} OK → {elf.name}", flush=True)
        return str(elf)
    return None


def rtl_run(cfg, elf_path, cm_bin):
    t = tag(cfg)
    out = f"dcache-cal/{t}"
    outdir = NPC_HOME / "ccout" / out
    outdir.mkdir(parents=True, exist_ok=True)
    cmd = f"{elf_path} {cm_bin} -M 500000000 -R {out}"
    print(f"[RTL RUN ] {t}", flush=True)
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True,
                       timeout=300)
    stats_path = outdir / "stats.json"
    if not stats_path.exists():
        print(f"[RTL RUN ] {t} no stats.json", flush=True)
        return None
    with open(stats_path) as f:
        data = json.load(f)
    pmu = data.get("pmu", {})
    bc = pmu.get("BlockedCause", {})
    ic = pmu.get("L1ICache", {})
    dc = pmu.get("L1DCache", {})
    return {
        "tag": t, **cfg,
        "cycles": bc.get("samples", 0),
        "insts": bc.get("NoStall", 0),
        "ipc": pmu.get("ipc", 0),
        "noinst": bc.get("NoInst", 0),
        "lsu_stall": bc.get("LsuStall", 0),
        "br_mispred": bc.get("BranchMispred", 0),
        "raw_stall": bc.get("RAW", 0),
        "icache_acc": ic.get("samples", 0),
        "icache_miss": ic.get("Miss", 0),
        "dcache_acc": dc.get("samples", 0),
        "dcache_miss": dc.get("Miss", 0),
    }


# ──── npSim ────
def npsim_run(cfg, outdir_base):
    t = tag(cfg)
    od = f"{outdir_base}/{t}"
    cmd = [
        str(NPSIM_BIN), str(TRACE),
        "--bpu-type", "bimodal",
        "--bpu-size", str(BPU["bp"]),
        "--btb-size", str(BPU["btb"]),
        "--ras-size", str(BPU["ras"]),
        "--l1i-size", "1024",
        "--l1i-blksize", "16",
        "--l1d-size", str(cfg["dc_size"]),
        "--l1d-blksize", str(cfg["dc_line"]),
        "--sdram-lat", "51",
        "--sdram-burst", "24",
        "--sram-lat", "1",
        "--ifq", "3",
        "--br-pen", "5",
        "--mmio-lat", "3",
        "--outdir", od,
        "--print-none",
    ]
    r = subprocess.run(cmd, capture_output=True, text=True,
                       cwd=str(NPSIM_HOME), timeout=60)
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
    ic = s1.get("iCache", {})
    dc = s1.get("dCache", {})
    cb = core.get("CycBreakdown", {})
    return {
        "tag": t, **cfg,
        "ipc": core.get("ipc", 0),
        "cycles": core.get("cycles", 0),
        "insts": core.get("insts", 0),
        "noinst": cb.get("NoInst", 0),
        "lsu_stall": cb.get("LsuStall", 0),
        "br_mispred": cb.get("BranchMispred", 0),
        "raw_stall": cb.get("RAW", 0),
        "icache_acc": ic.get("accesses", 0),
        "icache_miss": ic.get("misses", 0),
        "dcache_acc": dc.get("accesses", 0),
        "dcache_miss": dc.get("misses", 0),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--outdir", default="dcache_cal_ext")
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
            futs = {}
            for cfg in CONFIGS:
                t = tag(cfg)
                if t in elf_map:
                    f = pool.submit(rtl_run, cfg, elf_map[t],
                                    args.coremark_bin)
                    futs[f] = t
            for f in as_completed(futs):
                r = f.result()
                if r:
                    rtl_results.append(r)
                    print(f"[RTL DONE] {r['tag']} IPC={r.get('ipc','?')}"
                          f" dc_acc={r.get('dcache_acc',0)}"
                          f" dc_miss={r.get('dcache_miss',0)}", flush=True)
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
            futs = {}
            for cfg in CONFIGS:
                f = pool.submit(npsim_run, cfg, "dcache-cal-npsim2")
                futs[f] = tag(cfg)
            for f in as_completed(futs):
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

    hdr = (f"{'Tag':15s} {'RTL_IPC':>8s} {'npS_IPC':>8s} {'IPC%':>7s}"
           f" {'RTL_dcA':>8s} {'npS_dcA':>8s} {'dcA%':>7s}"
           f" {'RTL_dcM':>7s} {'npS_dcM':>7s} {'dcM%':>7s}"
           f" {'RTL_lsu':>8s} {'npS_lsu':>8s}")
    print(f"\n{hdr}")
    print("-" * len(hdr))
    for cfg in CONFIGS:
        t = tag(cfg)
        rtl = rtl_by_tag.get(t, {})
        nps = npsim_by_tag.get(t, {})
        r_ipc = rtl.get("ipc", 0)
        n_ipc = nps.get("ipc", 0)
        ipc_err = (n_ipc - r_ipc) / r_ipc * 100 if r_ipc > 0 else 0
        r_dca = rtl.get("dcache_acc", 0)
        n_dca = nps.get("dcache_acc", 0)
        dca_err = (n_dca - r_dca) / r_dca * 100 if r_dca > 0 else 0
        r_dcm = rtl.get("dcache_miss", 0)
        n_dcm = nps.get("dcache_miss", 0)
        dcm_err = (n_dcm - r_dcm) / r_dcm * 100 if r_dcm > 0 else 0
        r_lsu = rtl.get("lsu_stall", 0)
        n_lsu = nps.get("lsu_stall", 0)
        print(f"{t:15s} {r_ipc:8.4f} {n_ipc:8.4f} {ipc_err:+6.1f}%"
              f" {r_dca:8d} {n_dca:8d} {dca_err:+6.1f}%"
              f" {r_dcm:7d} {n_dcm:7d} {dcm_err:+6.1f}%"
              f" {r_lsu:8d} {n_lsu:8d}")


if __name__ == "__main__":
    main()
