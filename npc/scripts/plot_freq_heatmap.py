#!/usr/bin/env python3
"""Draw error heatmaps comparing RTL vs npSim at different frequencies.

Generates:
  1. Component error heatmap (3 freqs × 5 metrics)
  2. iCache sweep heatmaps at 500MHz and 1GHz
  3. dCache sweep heatmaps at 500MHz and 1GHz

Usage:
  python3 scripts/plot_freq_heatmap.py
"""

import json
import subprocess
import sys
from pathlib import Path

NPC_HOME = Path(__file__).resolve().parent.parent
NPSIM_HOME = NPC_HOME.parent / "npsim"
NPSIM_BIN = NPSIM_HOME / "build" / "npsim.elf"
TRACE = NPSIM_HOME / "tests" / "coremark-soc-ext.nptr.zst"
CCOUT = NPC_HOME / "ccout"
SIMOUT = NPSIM_HOME / "simout"

# ── RTL reference data (from ccout/cm-freq-*/stats.json) ──────────
RTL_FREQ = {}
for freq in [200, 500, 1000]:
    stats_file = CCOUT / f"cm-freq-{freq}" / "stats.json"
    if stats_file.exists():
        with open(stats_file) as f:
            d = json.load(f)
        pmu = d["pmu"]
        bc = pmu["BlockedCause"]
        RTL_FREQ[freq] = {
            "IPC": pmu["ipc"],
            "NoInst": bc["NoInst"],
            "LsuStall": bc["LsuStall"],
            "BranchMispred": bc["BranchMispred"],
            "RAW": bc["RAW"],
            "TotalCyc": bc["samples"],
        }


def run_npsim(freq, l1i_size=1024, l1i_blk=16,
              l1d_size=1024, l1d_blk=16):
    """Run npSim and return parsed stats dict from JSON output."""
    tag = (f"hm_f{freq}_i{l1i_size}x{l1i_blk}"
           f"_d{l1d_size}x{l1d_blk}")
    cmd = [
        str(NPSIM_BIN), str(TRACE),
        "--sdram-lat-us", "0.047", "--sdram-burst-us", "0.024",
        "--axi-ovhd-cyc", "4",
        "--sram-lat", "1", "--mmio-lat", "3",
        "--br-pen", "5", "--ifq", "3", "--freq-mhz", str(freq),
        "--bpu-type", "bimodal", "--bpu-size", "256",
        "--btb-size", "128", "--ras-size", "8",
        "--l1i-size", str(l1i_size), "--l1i-blksize", str(l1i_blk),
        "--l1d-size", str(l1d_size), "--l1d-blksize", str(l1d_blk),
        "--outdir", tag, "--print-none",
    ]
    subprocess.run(cmd, capture_output=True, text=True, timeout=30,
                   cwd=str(NPSIM_HOME))
    stats_file = NPSIM_HOME / "simout" / tag / "stats.json"
    with open(stats_file) as f:
        data = json.load(f)
    # stats0 is the reset-to-dump region
    return data["stats0"]


def pct_err(sim, rtl):
    if rtl == 0:
        return 0.0
    return (sim - rtl) / rtl * 100


def print_heatmap(title, row_labels, col_labels, data,
                  fmt="+.1f"):
    """Print an ASCII heatmap table with ANSI colors."""
    def colorize(val):
        absv = abs(val)
        if absv < 3:
            c = "\033[92m"  # green
        elif absv < 5:
            c = "\033[93m"  # yellow
        elif absv < 10:
            c = "\033[91m"  # red
        else:
            c = "\033[95m"  # magenta
        return f"{c}{val:{fmt}}%\033[0m"

    print(f"\n{'=' * 64}")
    print(f"  {title}")
    print(f"{'=' * 64}")

    col_w = 13
    header = f"{'':>10}" + "".join(
        f"{c:>{col_w}}" for c in col_labels)
    print(header)
    print("-" * (10 + col_w * len(col_labels)))

    for i, rl in enumerate(row_labels):
        row = f"{rl:>10}"
        for j in range(len(col_labels)):
            val = data[i][j]
            raw = f"{val:{fmt}}%"
            padding = col_w - len(raw)
            row += " " * padding + colorize(val)
        print(row)
    print()


def main():
    freqs = [200, 500, 1000]

    # ═══ Part 1: Component error heatmap ══════════════════════
    print("\n▶ Component error heatmap (3 freqs × 5 metrics)")
    col_labels = ["NoInst(IF)", "LsuStall", "BrMisPred",
                  "RAW", "IPC"]
    row_labels = [f"{f} MHz" for f in freqs]
    data = []
    for freq in freqs:
        sim = run_npsim(freq)
        core = sim["Core"]
        cb = core["CycBreakdown"]
        rtl = RTL_FREQ[freq]
        row = [
            pct_err(cb["NoInst"], rtl["NoInst"]),
            pct_err(cb["LsuStall"], rtl["LsuStall"]),
            pct_err(cb["BranchMispred"], rtl["BranchMispred"]),
            pct_err(cb["RAW"], rtl["RAW"]),
            pct_err(core["ipc"], rtl["IPC"]),
        ]
        data.append(row)

    print_heatmap("Component Error: npSim vs RTL (%)",
                  row_labels, col_labels, data)

    # ═══ Part 2: Cache sweep heatmaps ═════════════════════════
    cache_sizes = [256, 512, 1024]
    blk_sizes = [16, 32]

    # RTL sweep data dirs: icache-cal/ic{sz}_ln{blk},
    #                      dcache-cal/dc{sz}_ln{blk}
    #                      icache-cal-500/ic{sz}_ln{blk}, etc.
    for sweep, prefix, vary in [("iCache", "i", "ic"),
                                ("dCache", "d", "dc")]:
        for freq in [500, 1000]:
            row_labels = [f"{s}B" for s in cache_sizes]
            col_labels_c = [f"{b}B blk" for b in blk_sizes]
            data = []
            has_rtl = False
            for cs in cache_sizes:
                row = []
                for bs in blk_sizes:
                    if sweep == "iCache":
                        sim = run_npsim(
                            freq, l1i_size=cs, l1i_blk=bs)
                    else:
                        sim = run_npsim(
                            freq, l1d_size=cs, l1d_blk=bs)
                    sim_ipc = sim["Core"]["ipc"]

                    # Look for RTL data: freq-specific first
                    rtl_ipc = None
                    for tag in [
                        f"{prefix}cache-cal-{freq}/"
                        f"{vary}{cs}_ln{bs}",
                        f"{prefix}cache-cal/"
                        f"{vary}{cs}_ln{bs}",
                    ]:
                        f = CCOUT / tag / "stats.json"
                        if f.exists():
                            with open(f) as fh:
                                d = json.load(fh)
                            rtl_ipc = d["pmu"]["ipc"]
                            has_rtl = True
                            break
                    if rtl_ipc is None:
                        rtl_ipc = RTL_FREQ.get(
                            freq, RTL_FREQ[1000])["IPC"]

                    row.append(pct_err(sim_ipc, rtl_ipc))
                data.append(row)

            note = "" if has_rtl else " (no sweep data)"
            print_heatmap(
                f"{sweep} Sweep @ {freq} MHz{note}",
                row_labels, col_labels_c, data)


if __name__ == "__main__":
    main()
