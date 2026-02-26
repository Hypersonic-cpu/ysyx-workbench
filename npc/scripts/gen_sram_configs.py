#!/usr/bin/env python3
"""Generate OpenRAM config files for iCache SRAM dimensions.

Usage: python3 gen_sram_configs.py <output_dir>

Computes tag and data SRAM dimensions for direct-mapped iCaches
from 128B to 4KB with 16B lines (RV32, 32-bit address).
"""

import os, sys, math

ADDR_BITS = 32
LINE_BYTES = 16
LINE_BITS = LINE_BYTES * 8
WORD_BITS = 32

def cache_params(cache_bytes):
    num_sets = cache_bytes // LINE_BYTES
    idx_bits = int(math.log2(num_sets))
    off_bits = int(math.log2(LINE_BYTES))
    tag_bits = ADDR_BITS - idx_bits - off_bits
    tagv_bits = tag_bits + 1  # +1 valid bit
    return num_sets, tagv_bits, LINE_BITS

def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "libs/sram"
    os.makedirs(out_dir, exist_ok=True)

    configs = []
    for exp in range(7, 13):  # 128B .. 4096B
        sz = 1 << exp
        num_sets, tagv_bits, data_bits = cache_params(sz)
        configs.append((sz, num_sets, tagv_bits, data_bits))

    print(f"{'Cache':>6}  {'Sets':>5}  {'TagV':>5}  {'Data':>5}")
    print("-" * 36)
    for sz, ns, tv, db in configs:
        print(f"{sz:>5}B  {ns:>5}  {tv:>5}  {db:>5}")

        # Write OpenRAM config .py
        for name, width, depth in [
            (f"tag_{tv}x{ns}", tv, ns),
            (f"data_{db}x{ns}", db, ns),
        ]:
            cfg = os.path.join(out_dir, f"openram_{name}.py")
            with open(cfg, "w") as f:
                f.write(f"# OpenRAM config: {name}\n")
                f.write(f"word_size = {width}\n")
                f.write(f"num_words = {depth}\n")
                f.write(f'num_rw_ports = 1\n')
                f.write(f'num_r_ports = 0\n')
                f.write(f'num_w_ports = 0\n')
                f.write(f"tech_name = \"sky130A\"\n")
                f.write(f'nominal_corner_only = True\n')
                f.write(f'output_name = "sram_{name}"\n')
                f.write(f'output_path = "macro/sram_{name}"\n')

    print(f"\nOpenRAM config files written to {out_dir}/")
    print("To generate SRAM with OpenRAM:")
    print("  python3 libs/openram/openram.py <config.py>")

if __name__ == "__main__":
    main()
