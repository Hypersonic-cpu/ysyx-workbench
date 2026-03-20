# soc or npc mode
SOCMODE?=0
RTL_SCALA_ARG?=
# Core frequency in MHz — sets SIM_MHZ in simulator and drives ysyxSoC delayers
MHZ ?= 1000

# WARN: `--soc-mode` should not be specified manually
ifeq ($(SOCMODE),1)
override RTL_SCALA_ARG += --soc-mode
endif

# if this build involve NV Board
NVBENA?=0
DPRINTF?=0
LOGENA?=0
DIFFENA?=1
DBGENA?=1
# Simulation threads, not build threads
NTHREADS?=1

simccargs ?=
imagebin ?=
ARCH ?= riscv32im-ysyxsoc

PERF_COREMARK_ITER ?= 20
PERF_DHRYSTONE_ITER ?= 10000
PERF_DHRYSTONE_DIR ?= dhryston5k
PERF_MAX_CYCLE ?= 200000000
PERF_AM_BENCH ?= $(if $(AM_BENCH),$(AM_BENCH),$(abspath ../am-kernels/benchmarks))

ifneq ($(findstring ysyxsoc,$(ARCH)),)
PERF_SOCMODE = 1
else
PERF_SOCMODE = 0
endif

PRJ=rvproc
TOPNAME=INVALID_NAME
ifeq ($(SOCMODE),1)
TOPNAME=ysyxSoCFull
else
TOPNAME=rvCore
endif

## Directories
# project name (also source dir)
# dir for generated Verilog
BUILD_SV_DIR = ./build-sv/$(PRJ)
# dir for C++ compiled by Verilator
BUILD_SIM_DIR = ./build-sim/$(PRJ)
BUILD_OBJ_DIR = $(BUILD_SIM_DIR)/obj_dir
SRC_DPIC_DIR = $(PRJ)/dpic
# simulation elf or nvboard elf
SIM_BIN = $(BUILD_SIM_DIR)/$(PRJ).elf

SIM_CXX_DIR = $(PRJ)/sim-cxx

## NV Board related
NXDC_FILES = $(PRJ)/constr/$(TOPNAME).nxdc
INC_PATH  += $(abspath $(PRJ))/dpic $(abspath $(PRJ))/sim-cxx
VINC_PATH += $(SOC_HOME)/perip/uart16550/rtl $(SOC_HOME)/perip/spi/rtl $(SOC_HOME)/perip/psram

# Enable ccache for C++ compilation (verilated.mk reads OBJCACHE)
export OBJCACHE := ccache

## Verilator related
VERILATOR = verilator
VERILATOR_COVERAGE = verilator_coverage
ifeq ($(LOGENA),1)
VERILATOR_TRACE ?= --trace --trace-fst --trace-depth 99
else
VERILATOR_TRACE :=
endif
VERILATOR_CFLAGS += $(VERILATOR_TRACE) -DPRINTF_COND=$(DPRINTF)
VERILATOR_CFLAGS += -MMD -cc  --threads $(NTHREADS) --threads-dpi none \
										-O3 --x-assign fast --x-initial fast \
										--output-split 10000
										# --noassert
VERILATOR_CFLAGS += --timescale "1ns/1ns" --no-timing
VERILATOR_CFLAGS += --autoflush
INCFLAGS = $(addprefix -I, $(INC_PATH))
VINCFLAGS = $(addprefix -I, $(VINC_PATH))

# Stable flags (passed to Verilator, shared by generated + user files)
CXXFLAGS_BASE = $(INCFLAGS) -DTOP_NAME="\"V$(TOPNAME)\"" -std=c++23 -g -flto=auto -march=native -mtune=native
# Flags that change with build options (only compiled into user files)
CXXFLAGS_USER = -DLOGENA=$(LOGENA) -DDIFFENA=$(DIFFENA) -DDBGENA=$(DBGENA) -DNVBENA=$(NVBENA) \
								-DSOCMODE=$(SOCMODE) -DSIM_MHZ=$(MHZ)
CXXFLAGS += $(CXXFLAGS_BASE) $(CXXFLAGS_USER)

#json
CXXFLAGS_BASE += -I$(NPC_HOME)/libs/json/include

# capstone
LIBCAPSTONE = $(NEMU_HOME)/tools/capstone/repo/libcapstone.so.5
# LIBNEMU = $(NEMU_HOME)/build/build/riscv32-nemu-interpreter-so
CXXFLAGS_BASE += -I$(NEMU_HOME)/tools/capstone/repo/include
$(LIBCAPSTONE):
	$(MAKE) -C tools/capstone

# NVBoard related
SRC_AUTO_BIND = $(abspath $(BUILD_SIM_DIR)/auto_bind.cpp)
$(SRC_AUTO_BIND): $(NXDC_FILES)
	python3 $(NVBOARD_HOME)/scripts/auto_pin_bind.py $^ $@

VSRCS = $(shell find $(abspath $(BUILD_SV_DIR)) -name "*.sv" -or -name "*.v")
VSOC = $(shell find "$(SOC_HOME)/perip" -name "*.sv" -or -name "*.v") $(SOC_HOME)/build/ysyxSoCFull.v

ifeq ($(SOCMODE),1)
VSRCS += $(VSOC)
endif

# FILELIST := $(shell find $(abspath $(BUILD_SV_DIR)/filelist.f))
CSRCS += $(shell find $(abspath $(SIM_CXX_DIR)) -name "*.cc" -or -name "*.cpp")
# DPIC_SRCS = $(shell find $(abspath $(SRC_DPIC_DIR)) -name "*.cc" -or -name "*.cpp")

LDFLAGS +=  -flto=auto
ifeq ($(NVBENA),0)
NVB_ARCHIVE=
else
CXXFLAGS_BASE += -I$(NVBOARD_HOME)/include
include $(NVBOARD_HOME)/scripts/nvboard.mk
NVB_ARCHIVE=$(NVBOARD_ARCHIVE)
CSRCS += $(SRC_AUTO_BIND)
endif

# When compilation flags change, invalidate user object files only.
# Verilator-generated objects use only CXXFLAGS_BASE (stable) so ccache
# handles them without invalidation.
SIM_FLAGS_STAMP = $(BUILD_SIM_DIR)/.sim_flags_$(LOGENA)_$(DIFFENA)_$(DBGENA)_$(NVBENA)_$(SOCMODE)_$(MHZ)
USER_OBJ_NAMES = $(notdir $(patsubst %.cc,%.o,$(filter %.cc,$(CSRCS))) \
                          $(patsubst %.cpp,%.o,$(filter %.cpp,$(CSRCS))))
$(SIM_FLAGS_STAMP): prebuild
	@rm -f $(BUILD_SIM_DIR)/.sim_flags_* $(BUILD_SIM_DIR)/.sim_mhz_*
	@cd $(BUILD_OBJ_DIR) && rm -f $(USER_OBJ_NAMES) 2>/dev/null; true
	@rm -f $(SIM_BIN)
	@touch $@

$(SIM_BIN): $(SIM_FLAGS_STAMP) $(CSRCS) $(NVB_ARCHIVE)
	$(VERILATOR) -j $(VERILATOR_CFLAGS) \
		$(VSRCS) \
		--top-module $(TOPNAME) \
		$(CSRCS) $(NVB_ARCHIVE) $(VINCFLAGS) \
		$(addprefix -CFLAGS , $(CXXFLAGS_BASE)) $(addprefix -LDFLAGS , $(LDFLAGS)) \
		--Mdir $(BUILD_OBJ_DIR) --exe -o $(abspath $(SIM_BIN))
	@echo '$(USER_OBJ_NAMES): CPPFLAGS += $(CXXFLAGS_USER)' > $(BUILD_OBJ_DIR)/user_flags.mk
	+$(MAKE) -C $(BUILD_OBJ_DIR) -f V$(TOPNAME).mk -f user_flags.mk -j OBJCACHE=ccache

printhook:

	# @echo
	# @echo "INCFLAGS" $(INC_PATH)
	# @echo "CXXFLAGS" $(CXXFLAGS)
	# @echo "CSRCS" $(CSRCS)
	# @echo "COMMON" $(SRCS_COMMON)
	# @echo

test:
	# $(call git_commit, "test RTL")
	mill -i $(PRJ).test.testOnly $(PRJ).test.$(TOPNAME)Spec

runam:
	# $(call git_commit, "run with AM")
	mill -i $(PRJ).test.testOnly $(PRJ).test.$(TOPNAME)Am

ifeq ($(SOCMODE),1)
# For SOC mode: rebuild ysyxSoCFull.v when MHZ changes (affects delayer timing).
# A stamp file named after the frequency tracks when the SoC was last elaborated.
SOC_MHZ_STAMP = $(SOC_HOME)/build/.soc_mhz_$(MHZ)
compile: prebuild $(SOC_MHZ_STAMP) verilog $(SIM_BIN)
$(SOC_MHZ_STAMP):
	@rm -f $(SOC_HOME)/build/.soc_mhz_*
	@rm -f $(SOC_HOME)/build/ysyxSoCFull.v
	$(MAKE) -C $(SOC_HOME) verilog MHZ=$(MHZ)
	@touch $(SOC_MHZ_STAMP)
	@rm -f $(SIM_BIN)
else
compile: prebuild verilog $(SIM_BIN)
endif
	@echo "Compiled:" $(SIM_BIN)

run: printhook $(LIBCAPSTONE) compile
	@echo "imagebin = $(imagebin)"
	$(SIM_BIN) $(imagebin) $(simccargs)

runonly: prebuild $(SIM_BIN)
	$(SIM_BIN) $(imagebin) $(simccargs)

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
	core_rel="$$metric_tag/coremark20"; \
	dhry_rel="$$metric_tag/$(PERF_DHRYSTONE_DIR)"; \
	core_log="$$metric_root/coremark20/run.log"; \
	dhry_log="$$metric_root/$(PERF_DHRYSTONE_DIR)/run.log"; \
	summary_log="$$metric_root/summary.txt"; \
	mkdir -p "$$metric_root/coremark20" "$$metric_root/$(PERF_DHRYSTONE_DIR)"; \
	echo "[1/5] Build CoreMark (ITERATION=$(PERF_COREMARK_ITER))"; \
	$(MAKE) -C "$(PERF_AM_BENCH)/coremark" ARCH="$(ARCH)" MHZ="$(MHZ)" ITERATION="$(PERF_COREMARK_ITER)" insert-arg; \
	echo "[2/5] Build Dhrystone (ITERATION=$(PERF_DHRYSTONE_ITER))"; \
	$(MAKE) -C "$(PERF_AM_BENCH)/dhrystone" ARCH="$(ARCH)" MHZ="$(MHZ)" ITERATION="$(PERF_DHRYSTONE_ITER)" insert-arg; \
	echo "[3/5] Compile simulator (SOCMODE=$(PERF_SOCMODE), MHZ=$(MHZ), ARCH=$(ARCH))"; \
	$(MAKE) compile SOCMODE="$(PERF_SOCMODE)" MHZ="$(MHZ)" RTL_SCALA_ARG="$(RTL_SCALA_ARG)"; \
	core_bin="$(PERF_AM_BENCH)/coremark/build/coremark-$(ARCH).bin"; \
	dhry_bin="$(PERF_AM_BENCH)/dhrystone/build/dhrystone-$(ARCH).bin"; \
	echo "[4/5] Run CoreMark -> $$core_log"; \
	./$(SIM_BIN) "$$core_bin" -M "$(PERF_MAX_CYCLE)" --rec-outdir "$$core_rel" 2>&1 | tee "$$core_log"; \
	echo "[5/5] Run Dhrystone -> $$dhry_log"; \
	./$(SIM_BIN) "$$dhry_bin" -M "$(PERF_MAX_CYCLE)" --rec-outdir "$$dhry_rel" 2>&1 | tee "$$dhry_log"; \
	{ \
		echo "========== metric summary =========="; \
		echo "Output dir: $$metric_root"; \
		echo; \
		for item in "CoreMark:$$core_log" "Dhrystone:$$dhry_log"; do \
			name="$${item%%:*}"; \
			log="$${item#*:}"; \
			echo "[$$name]"; \
			grep -E "CoreMark (PASS|FAIL)|Dhrystone (PASS|FAIL)" "$$log" | tail -n 1 || true; \
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
		done; \
	} | tee "$$summary_log"; \
	echo "Saved logs/metrics to $$metric_root"

verilog: $(SCALA_SOURCES)
	$(call git_commit, "generate verilog")
	rm -rf $(BUILD_SV_DIR)
	mkdir -p $(BUILD_SV_DIR)
	mill -i $(PRJ).runMain Elaborate --target-dir $(BUILD_SV_DIR) --debug --no-sta $(RTL_SCALA_ARG)
	@echo "Verilog written to " $(BUILD_SV_DIR)
	@sed -i '/^\/\/ ----- 8< -----/,$$d' $(BUILD_SV_DIR)/*.sv
	@find $(BUILD_SV_DIR) -type f -name "*.v" -o -name "*.sv" -print0 | xargs -0 sed -i -e 's/_\(aw\|ar\|w\|r\|b\)_\(\|bits_\)/_\1/g'
	@sed -i 's/\(wire.*_collect_io_wbSide_bits_forward_pc\);/\1 \/* verilator public_flat_rd *\/;/' $(BUILD_SV_DIR)/rvCore.sv
	@cp $(SRC_DPIC_DIR)/PMemBox.sv $(BUILD_SV_DIR)/PMemBox.sv
	@if grep -q 'sram_1rw_' $(BUILD_SV_DIR)/*.sv 2>/dev/null; then \
	  mkdir -p $(SRAM_DIR) && \
	  python3 scripts/gen_sram_lib.py $(SRAM_LIB) $(BUILD_SV_DIR) && \
	  cp $(SRAM_WRAP) $(BUILD_SV_DIR)/sram_wrappers.sv; \
	fi
	@echo "Memory port renaming done"

rtlsta: $(SCALA_SOURCES)
	$(call git_commit, "generate verilog")
	rm -rf $(BUILD_SV_DIR)
	mkdir -p $(BUILD_SV_DIR)
	mill -i $(PRJ).runMain ElaborSta --no-debug --sta $(RTL_SCALA_ARG)
	@sed -i '/^\/\/ ----- 8< -----/,$$d' $(BUILD_SV_DIR)/*.sv
	@find $(BUILD_SV_DIR) -type f -name "*.v" -o -name "*.sv" -print0 | xargs -0 sed -i -e 's/_\(aw\|ar\|w\|r\|b\)_\(\|bits_\)/_\1/g'
	@cp $(SRC_DPIC_DIR)/FakeEcallBox.sv $(BUILD_SV_DIR)/FakeEcallBox.sv
	@if grep -q 'sram_1rw_' $(BUILD_SV_DIR)/*.sv 2>/dev/null; then \
	  mkdir -p $(SRAM_DIR) && \
	  python3 scripts/gen_sram_lib.py $(SRAM_LIB) $(BUILD_SV_DIR) && \
	  cp $(SRAM_BB) $(BUILD_SV_DIR)/sram_blackbox.sv && \
	  cp $(SRAM_LIB) $(BUILD_SV_DIR)/sram_1rw.lib; \
	fi

help:
	mill -i $(PRJ).runMain Elaborate --help

reformat:
	mill -i __.reformat

checkformat:
	mill -i __.checkFormat

bsp:
	mill -i mill.bsp.BSP/install

idea:
	mill -i mill.idea.GenIdea/idea

clean:
	-rm -rf $(BUILD_SV_DIR) $(BUILD_SIM_DIR)

## Formal verification — iCache
FORMAL_TOOL_DIR   = formal
FORMAL_ICACHE_DIR = formal/icache
FORMAL_ICACHE_SBY = $(FORMAL_ICACHE_DIR)/iCacheFormal.sby

formal-icache:
	@echo "Generating iCache formal SV"
	@rm -f $(FORMAL_ICACHE_DIR)/*.sv
	mill -i $(PRJ).runMain ElaborateFormal --target-dir $(FORMAL_ICACHE_DIR)/
	@echo "Copying template .sby file"
	@cat $(FORMAL_TOOL_DIR)/iCache-sby.md | awk '/^```/{flag=!flag; next} flag' > $(FORMAL_ICACHE_SBY)
	@echo "Patching SV for Yosys compatibility"
	@python3 $(FORMAL_TOOL_DIR)/patch_sv.py $(FORMAL_ICACHE_DIR)
	@echo "Running SymbiYosys"
	cd $(NPC_HOME) && sby -f $(FORMAL_ICACHE_SBY) bmc && cd -

## Formal verification — IntMultiplier
FORMAL_MUL_DIR = formal/mul
FORMAL_MUL_SBY = $(FORMAL_MUL_DIR)/IntMulFormal.sby

formal-mul:
	@echo "Generating IntMultiplier formal SV"
	@mkdir -p $(FORMAL_MUL_DIR)
	@rm -f $(FORMAL_MUL_DIR)/*.sv
	mill -i $(PRJ).runMain ElaborateFormalMul --target-dir $(FORMAL_MUL_DIR)/
	@echo "Copying template .sby file"
	@cat $(FORMAL_TOOL_DIR)/IntMul-sby.md | awk '/^```/{flag=!flag; next} flag' > $(FORMAL_MUL_SBY)
	@echo "Patching SV for Yosys compatibility"
	@python3 $(FORMAL_TOOL_DIR)/patch_sv.py $(FORMAL_MUL_DIR) IntMulFormal
	@echo "Running SymbiYosys"
	cd $(NPC_HOME) && sby -f $(FORMAL_MUL_SBY) bmc && cd -

## SRAM library generation
SRAM_DIR = libs/sram
SRAM_LIB = $(SRAM_DIR)/sram_1rw.lib
SRAM_BB  = $(SRAM_DIR)/sram_1rw.blackbox.sv
SRAM_WRAP = $(SRAM_DIR)/sram_1rw.wrappers.sv

sramlib:
	@mkdir -p $(SRAM_DIR)
	@python3 scripts/gen_sram_lib.py $(SRAM_LIB) $(BUILD_SV_DIR) 2>&1 || \
	 python3 scripts/gen_sram_lib.py $(SRAM_LIB)

.PHONY: test runam verilog help reformat checkformat clean prebuild sim printhook formal-icache formal-mul sramlib compile perf-metric

prebuild:
	@mkdir -p $(BUILD_OBJ_DIR)
	@mkdir -p $(BUILD_SIM_DIR)

-include ../Makefile
