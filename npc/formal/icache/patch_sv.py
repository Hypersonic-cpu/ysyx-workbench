#!/usr/bin/env python3
"""Patch CIRCT-generated SystemVerilog for Yosys compatibility.

CIRCT's `--verification-flavor=immediate` emits:
    assume(cond)
        else $error("...");

Yosys's native Verilog frontend cannot parse the `else $error(...)` clause
on immediate assert/assume/cover statements. This script strips those
clauses, leaving just `assert(cond);` / `assume(cond);`.
"""

import glob, re, sys, os

def patch_file(path):
    with open(path, 'r') as f:
        text = f.read()

    # Match:  assert/assume/cover(...) \n   else $error("...", args);
    # The $error call may span multiple lines (e.g. formatted strings).
    # Strategy: find `else $error(` and consume until the matching `);\n`
    patched = re.sub(
        r'((?:assert|assume|cover)\s*\([^;]*?\))\s*\n\s*else\s+\$error\(.*?\);[^\n]*',
        r'\1;',
        text,
        flags=re.DOTALL
    )
    with open(path, 'w') as f:
        f.write(patched)


def inject_initial_reset(path):
    """Inject initial reset constraints into the iCacheFormal module.

    Chisel uses synchronous reset, so RegInit values are set inside
    `if (reset)` blocks on clock edges. However, registers without explicit
    reset (e.g., RegNext) derive their values from combinational logic.
    After only 1 reset cycle, these registers hold values computed from the
    arbitrary pre-reset state. After 2 reset cycles, they hold values
    computed from the properly-reset registers — a fully consistent state.

    We inject:
      initial assume(reset);                      -- step 0: reset=1
      always @(posedge clock)                      -- step 1: reset=1
        if (!_formal_past_valid) assume(reset);
    This gives 2 rising edges with reset=1, after which all registers
    (including RegNext) are in a known state.
    """
    with open(path, 'r') as f:
        text = f.read()

    # Only inject into the file containing module iCacheFormal
    if 'module iCacheFormal(' not in text:
        return

    inject = (
        '  // ── Formal: 2-cycle initial reset + no re-assert ───────────────\n'
        '  // Step 0-1: reset=1 (forced). Step 2+: reset=0 (forced).\n'
        '  // This gives RegNext registers a clean input and prevents the\n'
        '  // solver from toggling reset mid-sequence (which would reset the\n'
        '  // DUT but not the golden model, causing false mismatches).\n'
        '  initial assume(reset);\n'
        '  reg [1:0] _formal_rst_cnt = 0;\n'
        '  reg       _formal_rst_done = 0;\n'
        '  always @(posedge clock) begin\n'
        '    if (_formal_rst_cnt < 2\'d2)\n'
        '      assume(reset);\n'
        '    if (reset && _formal_rst_cnt < 2\'d2)\n'
        '      _formal_rst_cnt <= _formal_rst_cnt + 1;\n'
        '    if (!reset)\n'
        '      _formal_rst_done <= 1;\n'
        '    if (_formal_rst_done)\n'
        '      assume(!reset);\n'
        '  end\n\n'
    )
    patched = text.replace(
        'always @(posedge clock)',
        inject + 'always @(posedge clock)',
        1  # only the first occurrence
    )
    with open(path, 'w') as f:
        f.write(patched)


if __name__ == '__main__':
    sv_dir = sys.argv[1] if len(sys.argv) > 1 else '.'
    for sv in glob.glob(os.path.join(sv_dir, '*.sv')):
        patch_file(sv)
    # Second pass: inject initial reset assumption
    for sv in glob.glob(os.path.join(sv_dir, '*.sv')):
        inject_initial_reset(sv)
