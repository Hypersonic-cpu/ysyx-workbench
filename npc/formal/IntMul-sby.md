## `.sby` file for IntMultiplier formal verification

```
# SymbiYosys configuration for IntMultiplier formal verification
# Run:  sby -f IntMulFormal.sby

[tasks]
bmc

[options]
bmc: mode bmc
# Pipeline is 3 stages deep; 2 reset cycles + 8 active cycles covers
# back-to-back operations with all four MUL variants.
bmc: depth 10

[engines]
bmc: abc bmc3

[script]
# auto-updated from filelist.f by patch_sv.py
read -sv IntMultiplier.sv
read -sv IntMulFormal.sv
prep -top IntMulFormal

[files]
formal/mul/IntMultiplier.sv
formal/mul/IntMulFormal.sv
```

Where `[script]` and `[files]` sections are automatically updated from
`filelist.f` by `patch_sv.py`.
