## `.sby` file for iCache formal verification

```
# SymbiYosys configuration for iCache formal verification
# Run:  sby -f iCacheFormal.sby

[tasks]
bmc

[options]
bmc: mode bmc
# A cache miss needs ~7 cycles to complete (1 for ar, 4 for burst, 1 for
# fill, 1 for response).
bmc: depth 20

[engines]
bmc: smtbmc z3

[script]
# Read all generated SystemVerilog files (auto-updated from filelist.f)
read -sv tagArr_8x25.sv
read -sv dataArr_8x128.sv
read -sv iCache.sv
read -sv ram_4x32.sv
read -sv Queue4_UInt32.sv
read -sv iCacheFormal.sv
# Elaborate with iCacheFormal as top
prep -top iCacheFormal

[files]
formal/icache/tagArr_8x25.sv
formal/icache/dataArr_8x128.sv
formal/icache/iCache.sv
formal/icache/ram_4x32.sv
formal/icache/Queue4_UInt32.sv
formal/icache/iCacheFormal.sv
```

Where `[script]` and `[files]` sections are automatically updated from `filelist.f` by `update_sby.py`.
