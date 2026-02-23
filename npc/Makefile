# soc or npc mode
SOCMODE?=0
RTL_SCALA_ARG?=

NPSIM_ACTIVE=0
ifeq ($(SOCMODE),1)
RTL_SCALA_ARG += --soc-mode
else
include ../npsim/Makefile
endif
# if this build involve NV Board
NVBENA ?= 0
DPRINTF?=0
LOGENA?=0
DIFFENA?=1
DBGENA?=1
NTHREADS?=1

simccargs ?=
mrombin ?=

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

## Verilator related
VERILATOR = verilator
VERILATOR_COVERAGE = verilator_coverage
VERILATOR_TRACE ?= --trace --trace-fst --trace-depth 99
VERILATOR_CFLAGS += $(VERILATOR_TRACE) -DPRINTF_COND=$(DPRINTF)
VERILATOR_CFLAGS += -MMD --build -cc  --threads $(NTHREADS) --threads-dpi none \
										-O3 --x-assign fast --x-initial fast
										# --noassert
VERILATOR_CFLAGS += --timescale "1ns/1ns" --no-timing
VERILATOR_CFLAGS += --autoflush
INCFLAGS = $(addprefix -I, $(INC_PATH))
VINCFLAGS = $(addprefix -I, $(VINC_PATH))
CXXFLAGS += $(INCFLAGS) -DTOP_NAME="\"V$(TOPNAME)\"" -std=c++23 -g
CXXFLAGS += -DLOGENA=$(LOGENA) -DDIFFENA=$(DIFFENA) -DDBGENA=$(DBGENA) -DNVBENA=$(NVBENA)
CXXFLAGS += -DSOCMODE=$(SOCMODE)

#json
CXXFLAGS += -I$(NPC_HOME)/libs/json/include

# capstone
LIBCAPSTONE = $(NEMU_HOME)/tools/capstone/repo/libcapstone.so.5
# LIBNEMU = $(NEMU_HOME)/build/build/riscv32-nemu-interpreter-so
CXXFLAGS += -I$(NEMU_HOME)/tools/capstone/repo/include
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

ifeq ($(NVBENA),0)
NVB_ARCHIVE=
LDFLAGS=
else
CXXFLAGS += -I$(NVBOARD_HOME)/include
include $(NVBOARD_HOME)/scripts/nvboard.mk
NVB_ARCHIVE=$(NVBOARD_ARCHIVE)
CSRCS += $(SRC_AUTO_BIND)
endif

$(SIM_BIN): $(CSRCS) $(NVB_ARCHIVE)
	@rm -rf $(BUILD_OBJ_DIR)
	$(VERILATOR) $(VERILATOR_CFLAGS) \
		$(VSRCS) \
		--top-module $(TOPNAME) \
		$^ $(VINCFLAGS) \
		$(addprefix -CFLAGS , $(CXXFLAGS)) $(addprefix -LDFLAGS , $(LDFLAGS)) \
		--Mdir $(BUILD_OBJ_DIR) --exe -o $(abspath $(SIM_BIN))

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

run: verilate verilog $(LIBCAPSTONE) printhook $(SIM_BIN)
	# $(call git_commit, "run with AM via verilator cc")
	@echo "MROMBIN = $(mrombin)"
	$(SIM_BIN) $(mrombin) $(simccargs)

runonly: verilate $(SIM_BIN)
	# $(call git_commit, "run with AM via verilator cc")
	$(SIM_BIN) $(mrombin) $(simccargs)

verilog: $(SCALA_SOURCES)
	$(call git_commit, "generate verilog")
	mkdir -p $(BUILD_SV_DIR)
	mill -i $(PRJ).runMain Elaborate --target-dir $(BUILD_SV_DIR) $(RTL_SCALA_ARG)
	@echo "Verilog written to " $(BUILD_SV_DIR)
	@echo "Apply memory port changes for SoC"
	@find $(BUILD_SV_DIR) -type f -name "*.v" -o -name "*.sv" -print0 | xargs -0 sed -i -e 's/_\(aw\|ar\|w\|r\|b\)_\(\|bits_\)/_\1/g'
	@cp $(SRC_DPIC_DIR)/PMemBox.sv $(BUILD_SV_DIR)/PMemBox.sv
	@echo "Memory port renaming done"

rtlsta: $(SCALA_SOURCES)
	$(call git_commit, "generate verilog") # WARN: Only allow tracking here
	mkdir -p $(BUILD_SV_DIR)
	mill -i $(PRJ).runMain ElaborSta
	@find $(BUILD_SV_DIR) -type f -name "*.v" -o -name "*.sv" -print0 | xargs -0 sed -i 's/^\.\/.*\.sv//'

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

.PHONY: test runam verilog help reformat checkformat clean verilate sim printhook formal-icache

verilate:
	@mkdir -p $(BUILD_OBJ_DIR)
	@mkdir -p $(BUILD_SIM_DIR)

# sim: verilate verilog $(SIM_BIN)
# 	$(call git_commit, "sim RTL") # DO NOT REMOVE THIS LINE!!!
# 	$(SIM_BIN)

# WARN: DO NOT AUTO-CHANGE TO ANY OTHER BRANCHES!
# THAT INVOLVES UNPREDICTABLE.
commit:
	@git commit -am "npc: commit by Makefile script"

tracer:
	$(call git_commit, "manual trace") # WARN: Only allow tracking here

-include ../Makefile
