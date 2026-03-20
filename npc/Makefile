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

## Main
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
-include perf-metric.mk
