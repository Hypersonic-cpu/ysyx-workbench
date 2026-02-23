#!/bin/bash
set -eu

L1I_SIZES=( "512" "1024" )
L1I_ASSOC=( "1" )
L1I_BLKSZ=( "16" "32" )

SIMCC_BIN="$NPC_HOME/build-sim/rvproc/rvproc.elf"
BENCH_PATH="$AM_BENCH/coremark"
BENCH_IMGS="$BENCH_PATH/build/coremark-riscv32e-npc.bin"
BENCH_ARGS="test"
OUT_ROOT="$NPC_HOME/ccout/sweep-cache/"
mkdir -p $OUT_ROOT


make -C $BENCH_PATH ARCH=riscv32e-npc mainargs="$BENCH_ARGS"


for size in "${L1I_SIZES[@]}"; do
  for block in "${L1I_BLKSZ[@]}"; do
    for assoc in "${L1I_ASSOC[@]}"; do
      curr_out="$OUT_ROOT/l1i_${size}_blk${block}_assoc${assoc}"
      mkdir -p $curr_out
      make -C $NPC_HOME \
        RTL_SCALA_ARG="--l1i-size ${size} --l1i-blksize ${block} --l1i-assoc ${assoc}" \
        LOGENA=0 DIFFENA=0 DPRINTF=0 NVBENA=0 DBGENA=1 DPRINTF=0 \
        compile
      echo "$SIMCC_BIN $BENCH_IMGS"
    done
  done
done

wait
echo "== Cache Sweep Done =="
