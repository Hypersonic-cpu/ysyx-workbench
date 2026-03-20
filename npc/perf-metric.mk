PERF_MAX_CYCLE ?= 200000000
PERF_AM_BENCH ?= $(if $(AM_BENCH),$(AM_BENCH),$(abspath ../am-kernels/benchmarks))

ifneq ($(findstring ysyxsoc,$(ARCH)),)
PERF_SOCMODE = 1
else
PERF_SOCMODE = 0
endif

# Benchmark list for perf-metric.
# To add a new case (e.g. train):
# 1) append key to PERF_BENCH_LIST
# 2) define PERF_BENCH_<key>_{DIR,BIN,ITER,MAINARGS,LABEL,PASS_RE}
PERF_BENCH_LIST ?= coremark20 dhryston5k

PERF_BENCH_coremark20_DIR ?= coremark
PERF_BENCH_coremark20_BIN ?= coremark
PERF_BENCH_coremark20_ITER ?= 20
PERF_BENCH_coremark20_MAINARGS ?=
PERF_BENCH_coremark20_LABEL ?= CoreMark
PERF_BENCH_coremark20_PASS_RE ?= CoreMark[[:space:]]+(PASS|FAIL)

PERF_BENCH_dhryston5k_DIR ?= dhrystone
PERF_BENCH_dhryston5k_BIN ?= dhrystone
PERF_BENCH_dhryston5k_ITER ?= 10000
PERF_BENCH_dhryston5k_MAINARGS ?=
PERF_BENCH_dhryston5k_LABEL ?= Dhrystone
PERF_BENCH_dhryston5k_PASS_RE ?= Dhrystone[[:space:]]+(PASS|FAIL)

perf-metric: SHELL := /bin/bash
perf-metric:
	@set -euo pipefail; \
	repo_root="$(abspath ..)"; \
	ts="$$(date +%Y%m%d-%H-%M-%S)"; \
	if [ -n "$$(git -C "$$repo_root" status --porcelain)" ]; then \
		metric_tag="metric-$$ts"; \
		printf "\033[1;33m[WARN] Working tree has uncommitted changes. Commit hash is omitted.\033[0m\n"; \
	else \
		commit7="$$(git -C "$$repo_root" rev-parse --short=7 HEAD)"; \
		metric_tag="metric-$$commit7-$$ts"; \
	fi; \
	metric_root="ccout/$$metric_tag"; \
	summary_log="$$metric_root/summary.txt"; \
	rtl_flags="$$metric_root/rtl_flags.json"; \
	mkdir -p "$$metric_root"; \
	echo "[1/3] Build benchmark binaries"; \
	$(foreach b,$(PERF_BENCH_LIST),\
		mkdir -p "$$metric_root/$(b)"; \
		echo "  - $(b) (dir=$(PERF_BENCH_$(b)_DIR), ITERATION=$(PERF_BENCH_$(b)_ITER), mainargs='$(PERF_BENCH_$(b)_MAINARGS)')"; \
		$(MAKE) -C "$(PERF_AM_BENCH)/$(PERF_BENCH_$(b)_DIR)" \
			ARCH="$(ARCH)" MHZ="$(MHZ)" \
			ITERATION="$(PERF_BENCH_$(b)_ITER)" \
			mainargs="$(PERF_BENCH_$(b)_MAINARGS)" \
			insert-arg; \
	) \
	echo "[2/3] Compile simulator (SOCMODE=$(PERF_SOCMODE), MHZ=$(MHZ), ARCH=$(ARCH))"; \
	ELABORATE_CONFIG_OUT="$$rtl_flags" \
	$(MAKE) compile SOCMODE="$(PERF_SOCMODE)" MHZ="$(MHZ)" RTL_SCALA_ARG="$(RTL_SCALA_ARG)"; \
	echo "[3/3] Run benchmarks"; \
	$(foreach b,$(PERF_BENCH_LIST),\
		bench_log="$$metric_root/$(b)/run.log"; \
		rec_rel="$$metric_tag/$(b)"; \
		bench_bin_path="$(PERF_AM_BENCH)/$(PERF_BENCH_$(b)_DIR)/build/$(PERF_BENCH_$(b)_BIN)-$(ARCH).bin"; \
		echo "  - $(b) -> $$bench_log"; \
		./$(SIM_BIN) "$$bench_bin_path" -M "$(PERF_MAX_CYCLE)" --rec-outdir "$$rec_rel" 2>&1 | tee "$$bench_log"; \
	) \
	{ \
		echo "========== metric summary =========="; \
		echo "Output dir: $$metric_root"; \
		echo "Benchmarks: $(PERF_BENCH_LIST)"; \
		echo; \
		$(foreach b,$(PERF_BENCH_LIST),\
			log="$$metric_root/$(b)/run.log"; \
			echo "[$(PERF_BENCH_$(b)_LABEL)]"; \
			grep -E "$(PERF_BENCH_$(b)_PASS_RE)" "$$log" | tail -n 1 || true; \
			grep -E "InstRet [0-9]+ IPC [0-9.]+" "$$log" | tail -n 1 || true; \
			grep -E "iCache Miss Rate" "$$log" | tail -n 1 || true; \
			grep -E "dCache Miss Rate" "$$log" | tail -n 1 || true; \
			grep -E "BrPred Accuracy" "$$log" | tail -n 1 || true; \
			stall_line="$$(grep -E "BlockedCause\\s*:\\s*samples" "$$log" | tail -n 1 || true)"; \
			if [ -n "$$stall_line" ]; then \
				echo "$$stall_line" | awk '\
					{ \
						gsub(/,/, "", $$0); \
						samples = 0; n = 0; \
						for (i = 1; i <= NF; i++) { \
							if ($$i == "samples") samples = $$(i + 1); \
							if ($$i == "NoStall" || $$i == "NoInst" || $$i == "LsuStall" || $$i == "BranchMispred" || $$i == "RAW") { \
								name = $$i; val = $$(i + 1); \
								if (val ~ /^[0-9]+$$/) { \
									if (!(name in seen)) order[++n] = name; \
									seen[name] = 1; cnt[name] = val; \
								} \
							} \
						} \
						if (samples > 0) { \
							for (j = 1; j <= n; j++) { \
								k = order[j]; \
								printf("  StallCause %-12s %10d (%.2f%%)\n", k, cnt[k], 100.0 * cnt[k] / samples); \
							} \
						} else { \
							printf("  StallCause N/A\n"); \
						} \
					}'; \
			else \
				echo "  StallCause N/A"; \
			fi; \
			echo; \
		) \
		echo "Elaborate config saved to $$rtl_flags"; \
	} | tee "$$summary_log"; \
	echo "Saved logs/metrics to $$metric_root"
