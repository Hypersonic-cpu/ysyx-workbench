#!/usr/bin/env python3
"""Generate Liberty (.lib) files for sram_1rw black-box macros.

Area estimated via analytical 6T SRAM cell model (NanGate 45nm).
Each (WORD_SIZE, NUM_WORDS) pair produces a named cell in the .lib.

Usage:
  python3 gen_sram_lib.py <output.lib> [SV_DIR]
  Scans SV_DIR for sram_1rw instantiations and generates matching cells.
  Without SV_DIR, generates cells for all iCache configs 128B..4kB.
"""

import math
import re
import sys
from pathlib import Path

SRAM_CELL_UM2 = 0.346
SRAM_EFFICIENCY = 0.55

def sram_area(word_bits, num_words):
    bits = word_bits * num_words
    return bits * SRAM_CELL_UM2 / SRAM_EFFICIENCY


def cell_name(word_bits, num_words):
    return f"sram_1rw_{word_bits}x{num_words}"


def gen_cell(word_bits, num_words):
    addr_bits = max(int(math.log2(num_words)), 1)
    area = sram_area(word_bits, num_words)
    lines = []
    lines.append(f'    cell ("{cell_name(word_bits, num_words)}") {{')
    lines.append(f"      area : {area:.2f};")
    lines.append(f'      pin ("clk0") {{ direction : input; }}')
    lines.append(f'      pin ("csb0") {{ direction : input; }}')
    lines.append(f'      pin ("web0") {{ direction : input; }}')
    lines.append(f'      bus ("addr0") {{')
    lines.append(f"        bus_type : bus{addr_bits};")
    lines.append(f"        direction : input;")
    lines.append(f"      }}")
    lines.append(f'      bus ("din0") {{')
    lines.append(f"        bus_type : bus{word_bits};")
    lines.append(f"        direction : input;")
    lines.append(f"      }}")
    lines.append(f'      bus ("dout0") {{')
    lines.append(f"        bus_type : bus{word_bits};")
    lines.append(f"        direction : output;")
    lines.append(f"      }}")
    lines.append(f"    }}")
    return "\n".join(lines)


def cache_configs():
    """Yield (word_bits, num_words) for tag and data arrays."""
    for exp in range(7, 13):
        sz = 1 << exp
        for blk in (16, 32):
            nsets = sz // blk
            if nsets < 2:
                continue
            off = int(math.log2(blk))
            idx = int(math.log2(nsets))
            tagv = 32 - off - idx + 1
            yield (tagv, nsets)
            yield (blk * 8, nsets)


def scan_sv(sv_dir):
    """Scan SV files for sram_1rw_WxD instantiations."""
    pat = re.compile(r"sram_1rw_(\d+)x(\d+)\s")
    configs = set()
    for f in Path(sv_dir).glob("*.sv"):
        for m in pat.finditer(f.read_text()):
            configs.add((int(m.group(1)), int(m.group(2))))
    return configs


def gen_lib(configs, outfile):
    bus_types = set()
    for wb, nw in configs:
        ab = max(int(math.log2(nw)), 1)
        bus_types.add(ab)
        bus_types.add(wb)

    lines = []
    lines.append('library ("sram_1rw") {')
    lines.append('  technology (cmos);')
    lines.append('  delay_model : table_lookup;')
    lines.append('  time_unit : "1ns";')
    lines.append('  voltage_unit : "1V";')
    lines.append('  current_unit : "1mA";')
    lines.append('  capacitive_load_unit (1,pf);')
    lines.append('  nom_voltage : 1.1;')
    lines.append('  nom_process : 1;')
    lines.append('  nom_temperature : 25;')

    for w in sorted(bus_types):
        lines.append(f'  type ("bus{w}") {{')
        lines.append(f"    base_type : array;")
        lines.append(f"    data_type : bit;")
        lines.append(f"    bit_width : {w};")
        lines.append(f"    bit_from  : {w - 1};")
        lines.append(f"    bit_to    : 0;")
        lines.append(f"  }}")

    for wb, nw in sorted(configs):
        lines.append(gen_cell(wb, nw))

    lines.append("}")
    Path(outfile).write_text("\n".join(lines) + "\n")
    print(f"Wrote {outfile} ({len(configs)} cells)")
    for wb, nw in sorted(configs):
        print(f"  {cell_name(wb, nw)}: {sram_area(wb, nw):.0f} um2")


def gen_sv_wrappers(configs, outfile):
    """Generate Verilog wrapper modules (instantiate parameterized sram_1rw)."""
    lines = []
    for wb, nw in sorted(configs):
        ab = max(int(math.log2(nw)), 1)
        name = cell_name(wb, nw)
        lines.append(f"module {name} (")
        lines.append(f"  input              clk0,")
        lines.append(f"  input              csb0,")
        lines.append(f"  input              web0,")
        lines.append(f"  input  [{ab-1}:0] addr0,")
        lines.append(f"  input  [{wb-1}:0] din0,")
        lines.append(f"  output [{wb-1}:0] dout0")
        lines.append(f");")
        lines.append(f"  sram_1rw #(")
        lines.append(f"    .WORD_SIZE({wb}),")
        lines.append(f"    .NUM_WORDS({nw})")
        lines.append(f"  ) u0 (")
        lines.append(f"    .clk0(clk0), .csb0(csb0), .web0(web0),")
        lines.append(f"    .addr0(addr0), .din0(din0), .dout0(dout0)")
        lines.append(f"  );")
        lines.append(f"endmodule")
        lines.append("")
    Path(outfile).write_text("\n".join(lines))
    print(f"Wrote {outfile} ({len(configs)} wrappers)")


def gen_sv_blackbox(configs, outfile):
    """Generate blackbox stubs for yosys synthesis."""
    lines = []
    for wb, nw in sorted(configs):
        ab = max(int(math.log2(nw)), 1)
        name = cell_name(wb, nw)
        lines.append(f"(* blackbox *)")
        lines.append(f"module {name} (")
        lines.append(f"  input              clk0,")
        lines.append(f"  input              csb0,")
        lines.append(f"  input              web0,")
        lines.append(f"  input  [{ab-1}:0] addr0,")
        lines.append(f"  input  [{wb-1}:0] din0,")
        lines.append(f"  output [{wb-1}:0] dout0")
        lines.append(f");")
        lines.append(f"endmodule")
        lines.append("")
    Path(outfile).write_text("\n".join(lines))
    print(f"Wrote {outfile} ({len(configs)} blackbox stubs)")


def main():
    outfile = sys.argv[1] if len(sys.argv) > 1 else "libs/sram/sram_1rw.lib"
    if len(sys.argv) > 2:
        configs = scan_sv(sys.argv[2])
    else:
        configs = set(cache_configs())
    gen_lib(configs, outfile)
    base = Path(outfile).with_suffix("")
    gen_sv_wrappers(configs, str(base) + ".wrappers.sv")
    gen_sv_blackbox(configs, str(base) + ".blackbox.sv")


if __name__ == "__main__":
    main()
