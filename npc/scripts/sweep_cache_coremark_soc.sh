#!/bin/bash
set -eu

L1I_SIZES=( "256" "512" "1024" "4096" )
L1I_ASSOC=( "1" )
L1I_BLKSZ=( "8" "16" "32" "64" )

SIMCC_EXEC="$NPC_HOME/build-sim/rvproc/rvproc.elf"
SIMCC_PREF="$NPC_HOME/build-sim/rvproc/rvproc_soc_"

BENCH_PATH="$AM_BENCH/coremark"
BENCH_IMGS="$BENCH_PATH/build/coremark-riscv32e-ysyxsoc.bin"
BENCH_ARGS=""
OUT_NAME="sweep-cache-coremark-soc"

if [[ "$#" -gt 0 ]]; then
SKIP_FLAG="$1"
else
SKIP_FLAG=""
fi

make -C $BENCH_PATH ARCH=riscv32e-ysyxsoc insert-arg

TARGET_EXEC=( )
OUTDIR_EXEC=( )

for size in "${L1I_SIZES[@]}"; do
  for block in "${L1I_BLKSZ[@]}"; do
    for assoc in "${L1I_ASSOC[@]}"; do
      curr_suffix="l1i_${size}_blk${block}_assoc${assoc}"
      curr_out="$OUT_NAME/$curr_suffix"
      curr_exec="${SIMCC_PREF}${curr_suffix}.elf"
      mkdir -p "$NPC_HOME/ccout/$curr_out"

      if [[ "$SKIP_FLAG" != "--skip-build" ]]; then
        make -C $NPC_HOME SOCMODE=1 \
          RTL_SCALA_ARG="--l1i-size ${size} --l1i-blksize ${block} --l1i-assoc ${assoc}" \
          LOGENA=0 DIFFENA=0 DPRINTF=0 NVBENA=0 DBGENA=1 DPRINTF=0 \
          compile
        mv $SIMCC_EXEC $curr_exec
      fi

      TARGET_EXEC+=("$curr_exec")
      OUTDIR_EXEC+=("$curr_out")
    done
  done
done

echo "== SoC Cache Sweep (CoreMark) Start =="
MAX_JOBS=3

wait_jobs() {
  while [ "$(jobs -rp | wc -l)" -ge "$MAX_JOBS" ]; do
    sleep 1
  done
}

for i in "${!TARGET_EXEC[@]}"; do
  wait_jobs
  tar="${TARGET_EXEC[$i]}"
  out="${OUTDIR_EXEC[$i]}"
  echo "$tar $BENCH_IMGS -R $out"
  $tar $BENCH_IMGS -R $out &
done

wait
echo "== SoC Cache Sweep (CoreMark) Done =="
