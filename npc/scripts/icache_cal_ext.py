#!/usr/bin/env python3
"""iCache calibration: sweep RTL + npSim, compare.

Phase B-3: vary iCache size/line with fixed BPU+dCache.
Serial compile (RTL) → parallel sim (max 3) → npSim sweep → diff.
"""

import json
import os
import subprocess
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

# Fixed BPU + dCache
BPU = {"btb": 128, "bp": 256, "ras": 8}
DC = {"size": 1024, "line": 16}

# iCache sweep
IC_SIZES = [256, 512, 1024]
IC_LINES = [16, 32]

CONFIGS = []
for sz in IC_SIZES:
    for ln in IC_LINES:
        CONFIGS.append({"ic_size": sz, "ic_line": ln})


def tag(cfg):
    return f"ic{cfg['ic_size']}_ln{cfg['ic_line']}"


def rtl_build(cfg):
    t = tag(cfg)
    elf = NPC_HOME / "build-sim" / "rvproc" / f"rvproc_ic_{t}.elf"
    rtl_args = (f"--config-extended --bp-bimodal "
                f"--bp-entries {BPU['bp']} --btb-entries {BPU['btb']} "
                f"--ras-size {BPU['ras']} "
                f"--l1d-size {DC['size']} --l1d-blksize {DC['line']} "
                f"--l1i-size {cfg['ic_size']} "
                f"--l1i-blksize {cfg['ic_line']}")
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
        print(f"[RTL BUILD] {t} OK", flush=True)
        return str(elf)
    return None


def rtl_run(cfg, elf_path, cm_bin):
    t = tag(cfg)
    out = f"icache-cal/{t}"
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


def npsim_run(cfg, outdir_base):
    t = tag(cfg)
    od = f"{outdir_base}/{t}"
    cmd = [
        str(NPSIM_BIN), str(TRACE),
        "--bpu-type", "bimodal",
        "--bpu-size", str(BPU["bp"]),
        "--btb-size", str(BPU["btb"]),
        "--ras-size", str(BPU["ras"]),
        "--l1i-size", str(cfg["ic_size"]),
        "--l1i-blksize", str(cfg["ic_line"]),
        "--l1d-size", str(DC["size"]),
        "--l1d-blksize", str(DC["line"]),
        "--sdram-lat-us", "0.047", "--axi-ovhd-cyc", "4",
        "--sdram-burst-us", "0.024",
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
        "icache_spec_acc": ic.get("spec_accesses", 0),
        "icache_spec_miss": ic.get("spec_misses", 0),
        "dcache_acc": dc.get("accesses", 0),
        "dcache_miss": dc.get("misses", 0),
    }


def main():
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--outdir", default="icache_cal_ext")
    ap.add_argument("--coremark-bin", default=str(COREMARK_BIN))
    ap.add_argument("--skip-rtl-build", action="store_true")
    ap.add_argument("--skip-rtl-run", action="store_true")
    ap.add_argument("--skip-npsim", action="store_true")
    ap.add_argument("--max-parallel", type=int, default=3)
    args = ap.parse_args()

    outdir = Path(args.outdir)
    outdir.mkdir(parents=True, exist_ok=True)

    elf_map = {}
    if not args.skip_rtl_build:
        for cfg in CONFIGS:
            elf = rtl_build(cfg)
            if elf:
                elf_map[tag(cfg)] = elf
        with open(outdir / "elf_map.json", "w") as f:
            json.dump(elf_map, f, indent=2)
    else:
        p = outdir / "elf_map.json"
        if p.exists():
            with open(p) as f:
                elf_map = json.load(f)

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
                    print(f"[RTL DONE] {r['tag']} IPC={r['ipc']:.4f}"
                          f" ic_acc={r['icache_acc']}"
                          f" ic_miss={r['icache_miss']}", flush=True)
        with open(outdir / "rtl_results.json", "w") as f:
            json.dump(rtl_results, f, indent=2)
    else:
        p = outdir / "rtl_results.json"
        if p.exists():
            with open(p) as f:
                rtl_results = json.load(f)

    npsim_results = []
    if not args.skip_npsim:
        with ProcessPoolExecutor(max_workers=args.max_parallel) as pool:
            futs = {}
            for cfg in CONFIGS:
                f = pool.submit(npsim_run, cfg, "icache-cal-npsim2")
                futs[f] = tag(cfg)
            for f in as_completed(futs):
                r = f.result()
                if r:
                    npsim_results.append(r)
        with open(outdir / "npsim_results.json", "w") as f:
            json.dump(npsim_results, f, indent=2)
    else:
        p = outdir / "npsim_results.json"
        if p.exists():
            with open(p) as f:
                npsim_results = json.load(f)

    # Compare
    rtl_by = {r["tag"]: r for r in rtl_results}
    nps_by = {r["tag"]: r for r in npsim_results}

    hdr = (f"{'Tag':15s} {'RTL_IPC':>8s} {'npS_IPC':>8s} {'IPC%':>7s}"
           f" {'RTL_icA':>8s} {'npS_icA':>8s} {'icA%':>7s}"
           f" {'RTL_icM':>7s} {'npS_icM':>7s} {'icM%':>7s}"
           f" {'RTL_noI':>8s} {'npS_noI':>8s}")
    print(f"\n{hdr}")
    print("-" * len(hdr))
    for cfg in CONFIGS:
        t = tag(cfg)
        r = rtl_by.get(t, {})
        n = nps_by.get(t, {})
        r_ipc = r.get("ipc", 0)
        n_ipc = n.get("ipc", 0)
        ipc_err = (n_ipc - r_ipc) / r_ipc * 100 if r_ipc > 0 else 0
        r_ica = r.get("icache_acc", 0)
        n_ica = n.get("icache_acc", 0)
        ica_err = (n_ica - r_ica) / r_ica * 100 if r_ica > 0 else 0
        r_icm = r.get("icache_miss", 0)
        n_icm = n.get("icache_miss", 0)
        icm_err = (n_icm - r_icm) / r_icm * 100 if r_icm > 0 else 0
        r_noi = r.get("noinst", 0)
        n_noi = n.get("noinst", 0)
        print(f"{t:15s} {r_ipc:8.4f} {n_ipc:8.4f} {ipc_err:+6.1f}%"
              f" {r_ica:8d} {n_ica:8d} {ica_err:+6.1f}%"
              f" {r_icm:7d} {n_icm:7d} {icm_err:+6.1f}%"
              f" {r_noi:8d} {n_noi:8d}")


if __name__ == "__main__":
    main()
