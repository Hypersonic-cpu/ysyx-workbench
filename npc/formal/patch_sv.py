#!/usr/bin/env python3
"""Patch CIRCT-generated SystemVerilog for Yosys compatibility.

CIRCT's `--verification-flavor=immediate` emits:
    assume(cond)
        else $error("...");

Yosys's native Verilog frontend cannot parse the `else $error(...)` clause
on immediate assert/assume/cover statements. This script strips those
clauses, leaving just `assert(cond);` / `assume(cond);`.
"""

import glob
import os
import re
import sys


def patch_file(path):
    with open(path, "r") as f:
        text = f.read()

    # Find `else $error(` and consume until the matching `);\n`
    patched = re.sub(
        r"((?:assert|assume|cover)\s*\([^;]*?\))\s*\n\s*else\s+\$error\(.*?\);[^\n]*",
        r"\1;",
        text,
        flags=re.DOTALL,
    )
    with open(path, "w") as f:
        f.write(patched)


def inject_initial_reset(path, top_module="iCacheFormal"):
    """Force non-resettable Chisel register (RegNext etc.) in a known state.
    We inject:
      initial assume(reset);       -- step 0: reset=1
      always @(posedge clock)      -- step 1: reset=1
        if (!_formal_past_valid) assume(reset);
    This gives 2 rising edges with reset=1, after which all reg. are in a known state.
    """
    with open(path, "r") as f:
        text = f.read()

    if f"module {top_module}(" not in text:
        return

    inject = (
        "\n"
        "  // >> Formal: 2-cycle initial reset + no re-assert <<\n"
        "  initial assume(reset);\n"
        "  reg [1:0] _fv_rst_cnt = 0;\n"
        "  reg       _fv_rst_done = 0;\n"
        "  always @(posedge clock) begin\n"
        "    if (_fv_rst_cnt < 2'd2)\n"
        "      assume(reset);\n"
        "    if (reset && _fv_rst_cnt < 2'd2)\n"
        "      _fv_rst_cnt <= _fv_rst_cnt + 1;\n"
        "    if (!reset)\n"
        "      _fv_rst_done <= 1;\n"
        "    if (_fv_rst_done)\n"
        "      assume(!reset);\n"
        "  end\n"
    )
    # Insert right before endmodule of iCacheFormal
    patched = text.replace(
        "endmodule\n",
        inject + "endmodule\n",
        1,  # only the first occurrence (iCacheFormal module)
    )
    with open(path, "w") as f:
        f.write(patched)


def update_sby(sv_dir, top_module="iCacheFormal"):
    """Inject [script] and [files] sections in .sby from filelist.f."""
    sby_path = os.path.join(sv_dir, f"{top_module}.sby")
    fl_path = os.path.join(sv_dir, "filelist.f")
    if not os.path.exists(fl_path) or not os.path.exists(sby_path):
        return

    wrapper_name = f"{top_module}Wrapper"
    with open(fl_path) as f:
        sv_files = [l.strip() for l in f if l.strip() and wrapper_name not in l]

    with open(sby_path) as f:
        sby = f.read()

    read_lines = "\n".join(f"read -sv {fn}" for fn in sv_files)
    file_lines = "\n".join(f"{sv_dir}/{fn}" for fn in sv_files)

    script_section = f"[script]\n{read_lines}\nprep -top {top_module}"
    files_section = f"[files]\n{file_lines}"

    sby = re.sub(r"\[script\].*?(?=\n\[)", script_section + "\n", sby, flags=re.DOTALL)
    sby = re.sub(r"\[files\].*", files_section, sby, flags=re.DOTALL)

    with open(sby_path, "w") as f:
        f.write(sby)


if __name__ == "__main__":
    sv_dir = sys.argv[1] if len(sys.argv) > 1 else "."
    top_module = sys.argv[2] if len(sys.argv) > 2 else "iCacheFormal"
    for sv in glob.glob(os.path.join(sv_dir, "*.sv")):
        patch_file(sv)
    for sv in glob.glob(os.path.join(sv_dir, "*.sv")):
        inject_initial_reset(sv, top_module)
    update_sby(sv_dir, top_module)
