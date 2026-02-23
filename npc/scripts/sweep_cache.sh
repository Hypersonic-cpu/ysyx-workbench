#!/bin/bash
set -eu

L1I_SIZES=( "512" "1024" )
L1I_ASSOC=( "1" )
L1I_BLKSZ=( "16" "32" )

SIMCC_EXEC="$NPC_HOME/build-sim/rvproc/rvproc.elf"
SIMCC_PREF="$NPC_HOME/build-sim/rvproc/rvproc_"

BENCH_PATH="$AM_BENCH/coremark"
BENCH_IMGS="$BENCH_PATH/build/coremark-riscv32e-npc.bin"
BENCH_ARGS="test"
OUT_ROOT="$NPC_HOME/ccout/sweep-cache/"
mkdir -p $OUT_ROOT


if [[ "$#" -gt 1 ]]; then
SKIP_FLAG="$1"
else
SKIP_FLAG=""
fi

make -C $BENCH_PATH ARCH=riscv32e-npc mainargs="$BENCH_ARGS"

TARGET_EXEC=( )

for size in "${L1I_SIZES[@]}"; do
  for block in "${L1I_BLKSZ[@]}"; do
    for assoc in "${L1I_ASSOC[@]}"; do
      curr_suffix="l1i_${size}_blk${block}_assoc${assoc}"
      curr_out="$OUT_ROOT/$curr_suffix"
      mkdir -p $curr_out

      if [[ "$SKIP_FLAG" != "--skip-build" ]]; then
        make -C $NPC_HOME \
          RTL_SCALA_ARG="--l1i-size ${size} --l1i-blksize ${block} --l1i-assoc ${assoc}" \
          LOGENA=0 DIFFENA=0 DPRINTF=0 NVBENA=0 DBGENA=1 DPRINTF=0 \
          compile
      fi

      curr_exec="${SIMCC_PREF}${curr_suffix}.elf"
      mv $SIMCC_EXEC $curr_exec
      TARGET_EXEC+=("$curr_exec")
    done
  done
done

echo "== Cache Sweep Start =="
MAX_JOBS=$(( $(nproc) - 2 ))

wait_jobs() {
  while [ "$(jobs -rp | wc -l)" -ge "$MAX_JOBS" ]; do
    sleep 1
  done
}

for tar in "${TARGET_EXEC[@]}"; do
  wait_jobs
  echo "$tar $BENCH_IMGS"
  $tar $BENCH_IMGS &
done

wait
echo "== Cache Sweep Done =="
