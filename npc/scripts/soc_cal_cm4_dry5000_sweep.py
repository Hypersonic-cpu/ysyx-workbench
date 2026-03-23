#!/usr/bin/env python3
"""RTL sweep for SoC calibration matrix (23-Mar-2026).

This script is RTL-only and runs in npc/.
It compiles all ELFs first, then smoke-runs one case, then runs full sweep.

Required output layout:
  npc/ccout/23-Mar-2026-Cal/{cm2,dry2500}-{500,1000}MHz/<suffix>/stats.json
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import shutil
import subprocess
import sys
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path
from typing import Dict, Iterable, List, Tuple


def load_common(repo_root: Path):
    common_py = repo_root / "misc" / "soc_cal_23mar2026_common.py"
    if not common_py.exists():
        raise FileNotFoundError(f"Missing shared config: {common_py}")
    spec = importlib.util.spec_from_file_location("soc_cal_common", common_py)
    mod = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = mod
    spec.loader.exec_module(mod)
    return mod


def ensure_exists(path: Path, what: str) -> None:
    if not path.exists():
        raise FileNotFoundError(f"{what} not found: {path}")


def compile_one(
    npc_home: str,
    rtl_args: str,
    mhz: int,
    elf_dst: str,
    timeout_s: int,
) -> Tuple[bool, str]:
    elf = Path(elf_dst)
    if elf.exists():
        return True, "cached"

    cmd = [
        "make",
        "-C",
        npc_home,
        "compile",
        "ARCH=riscv32im-ysyxsoc",
        "SOCMODE=1",
        f"MHZ={mhz}",
        "DIFFENA=0",
        "DPRINTF=0",
        "LOGENA=0",
        "DBGENA=1",
        "NVBENA=0",
        f"RTL_SCALA_ARG={rtl_args}",
    ]
    p = subprocess.run(
        cmd,
        text=True,
        capture_output=True,
        timeout=timeout_s,
    )
    if p.returncode != 0:
        return False, (p.stdout + p.stderr)[-1200:]

    src = Path(npc_home) / "build-sim" / "rvproc" / "rvproc.elf"
    if not src.exists():
        return False, "rvproc.elf not generated"

    elf.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, elf)
    return True, "ok"


def run_one(
    npc_home: str,
    elf_path: str,
    bench_bin: str,
    run_tag: str,
    max_cycles: int,
    timeout_s: int,
) -> Tuple[str, bool, str]:
    stats = Path(npc_home) / "ccout" / run_tag / "stats.json"
    if stats.exists():
        return run_tag, True, "cached"

    stats.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        elf_path,
        bench_bin,
        "-M",
        str(max_cycles),
        "-R",
        run_tag,
    ]
    p = subprocess.run(
        cmd,
        text=True,
        capture_output=True,
        timeout=timeout_s,
        cwd=npc_home,
    )
    if p.returncode != 0 or not stats.exists():
        return run_tag, False, (p.stdout + p.stderr)[-1200:]

    return run_tag, True, "ok"


def parse_csv_ints(s: str) -> List[int]:
    vals = [x.strip() for x in s.split(",") if x.strip()]
    return [int(x) for x in vals]


def parse_csv_strs(s: str) -> List[str]:
    return [x.strip() for x in s.split(",") if x.strip()]


def main() -> int:
    repo_root = Path(__file__).resolve().parents[2]
    npc_home = repo_root / "npc"
    am_bench = repo_root / "am-kernels" / "benchmarks"
    common = load_common(repo_root)

    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--groups", default=common.default_groups_csv())
    ap.add_argument("--freqs", default="500,1000")
    ap.add_argument("--benches", default="cm2,dry2500")
    ap.add_argument("--jobs", type=int, default=3)
    ap.add_argument("--out-root", default="23-Mar-2026-Cal")
    ap.add_argument("--elf-subdir", default="cal-23-mar-2026")
    ap.add_argument("--max-cycles", type=int, default=500_000_000)
    ap.add_argument("--compile-timeout", type=int, default=1800)
    ap.add_argument("--run-timeout", type=int, default=1800)
    ap.add_argument("--limit-configs", type=int, default=0)
    ap.add_argument("--skip-compile", action="store_true")
    ap.add_argument("--skip-smoke", action="store_true")
    ap.add_argument("--skip-run", action="store_true")
    args = ap.parse_args()

    groups = common.parse_groups(args.groups)
    freqs = parse_csv_ints(args.freqs)
    benches = parse_csv_strs(args.benches)

    bench_map = {
        "cm2": am_bench / "coremark" / "build" / "coremark-riscv32im-ysyxsoc.bin",
        "dry2500": am_bench / "dhrystone" / "build" / "dhrystone-riscv32im-ysyxsoc.bin",
    }
    for b in benches:
        if b not in bench_map:
            raise ValueError(f"Unsupported bench '{b}', choose from {sorted(bench_map)}")
        ensure_exists(bench_map[b], f"bench binary ({b})")

    cfgs = common.configs_for_groups(groups, dedup=True)
    cfgs = sorted(cfgs, key=lambda c: common.canonical_suffix(c))
    if args.limit_configs > 0:
        cfgs = cfgs[: args.limit_configs]

    print(f"Groups: {groups}")
    print(f"Unique HW configs: {len(cfgs)}")
    print(f"Freqs: {freqs}")
    print(f"Benches: {benches}")
    print(f"Output root: npc/ccout/{args.out_root}")

    # Phase 1: compile all ELFs first.
    elf_map: Dict[Tuple[int, str], Path] = {}
    if not args.skip_compile:
        print("\n[Phase 1] Compile all ELFs (serial)")
        total = len(freqs) * len(cfgs)
        idx = 0
        for mhz in freqs:
            for cfg in cfgs:
                idx += 1
                suffix = common.canonical_suffix(cfg)
                elf = (
                    npc_home
                    / "build-sim"
                    / "rvproc"
                    / args.elf_subdir
                    / f"rvproc_{mhz}MHz_{suffix}.elf"
                )
                ok, msg = compile_one(
                    str(npc_home),
                    common.rtl_scala_arg(cfg),
                    mhz,
                    str(elf),
                    args.compile_timeout,
                )
                status = "OK" if ok else "FAIL"
                print(f"  [{idx}/{total}] {mhz}MHz {suffix}: {status}")
                if not ok:
                    print(msg)
                    return 2
                elf_map[(mhz, suffix)] = elf
    else:
        for mhz in freqs:
            for cfg in cfgs:
                suffix = common.canonical_suffix(cfg)
                elf = (
                    npc_home
                    / "build-sim"
                    / "rvproc"
                    / args.elf_subdir
                    / f"rvproc_{mhz}MHz_{suffix}.elf"
                )
                ensure_exists(elf, f"ELF ({mhz}MHz, {suffix})")
                elf_map[(mhz, suffix)] = elf

    # Smoke run one case to verify stats dump.
    if not args.skip_smoke and not args.skip_run:
        print("\n[Phase 2] Smoke run one case")
        smoke_cfg = cfgs[0]
        smoke_mhz = freqs[0]
        smoke_bench = benches[0]
        suffix = common.canonical_suffix(smoke_cfg)
        run_tag = f"{args.out_root}/{common.full_tag(smoke_bench, smoke_mhz, smoke_cfg)}"
        tag, ok, msg = run_one(
            str(npc_home),
            str(elf_map[(smoke_mhz, suffix)]),
            str(bench_map[smoke_bench]),
            run_tag,
            args.max_cycles,
            args.run_timeout,
        )
        print(f"  Smoke {tag}: {'OK' if ok else 'FAIL'}")
        if not ok:
            print(msg)
            return 3

    # Phase 3: full RTL run.
    tasks = []
    for mhz in freqs:
        for cfg in cfgs:
            suffix = common.canonical_suffix(cfg)
            elf = elf_map[(mhz, suffix)]
            for b in benches:
                run_tag = f"{args.out_root}/{common.full_tag(b, mhz, cfg)}"
                tasks.append((str(elf), str(bench_map[b]), run_tag))

    if args.skip_run:
        print("\n[Phase 3] Skipped (--skip-run)")
        return 0

    print(f"\n[Phase 3] Full RTL runs: {len(tasks)} tasks, jobs={args.jobs}")
    done = 0
    fail = 0
    with ProcessPoolExecutor(max_workers=args.jobs) as ex:
        futs = {
            ex.submit(
                run_one,
                str(npc_home),
                elf,
                bench,
                run_tag,
                args.max_cycles,
                args.run_timeout,
            ): run_tag
            for elf, bench, run_tag in tasks
        }
        total = len(futs)
        for fut in as_completed(futs):
            tag, ok, msg = fut.result()
            done += 1
            if not ok:
                fail += 1
            status = "OK" if ok else "FAIL"
            print(f"  [{done}/{total}] {tag}: {status}")
            if not ok:
                print(msg)

    print("\n[Summary]")
    print(f"  Total tasks : {len(tasks)}")
    print(f"  Failed      : {fail}")
    print(f"  Output base : {npc_home / 'ccout' / args.out_root}")
    return 0 if fail == 0 else 4


if __name__ == "__main__":
    sys.exit(main())
