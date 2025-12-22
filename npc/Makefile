include MakeAmRun.mk

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

# if this build involve NV Board 
WITH_NVBOARD ?= 0

ifeq ($(WITH_NVBOARD),0)
SIM_CXX_DIR = $(PRJ)/sim-cxx
else
SIM_CXX_DIR = $(PRJ)/sim-nvboard
endif

## NV Board related 
NXDC_FILES = $(PRJ)/constr/$(TOPNAME).nxdc
INC_PATH ?= $(abspath $(PRJ))/dpic $(abspath $(PRJ))/sim-cxx
VINC_PATH += $(SOC_HOME)/perip/uart16550/rtl $(SOC_HOME)/perip/spi/rtl $(SOC_HOME)/perip/psram

## Parallism 
NTHREADS?=1
## Verilator related
VERILATOR = verilator
VERILATOR_COVERAGE = verilator_coverage
VERILATOR_TRACE ?= --trace --trace-fst --trace-depth 99
DPRINTF?=0
LOGENA?=0
DIFFENA?=1
DBGENA?=1
VERILATOR_CFLAGS += $(VERILATOR_TRACE) -DPRINTF_COND=$(DPRINTF)
VERILATOR_CFLAGS += -MMD --build -cc  --threads $(NTHREADS) --threads-dpi none \
										-O3 --x-assign fast --x-initial fast --noassert
VERILATOR_CFLAGS += --timescale "1ns/1ns" --no-timing
VERILATOR_CFLAGS += --autoflush
INCFLAGS = $(addprefix -I, $(INC_PATH))
VINCFLAGS = $(addprefix -I, $(VINC_PATH))
CXXFLAGS += $(INCFLAGS) -DTOP_NAME="\"V$(TOPNAME)\"" -std=c++23 -g
CXXFLAGS += -DLOGENA=$(LOGENA) -DDIFFENA=$(DIFFENA) -DDBGENA=$(DBGENA)
simccargs ?= 
mrombin ?= 

# capstone
LIBCAPSTONE = $(NEMU_HOME)/tools/capstone/repo/libcapstone.so.5
CXXFLAGS += -I$(NEMU_HOME)/tools/capstone/repo/include
$(LIBCAPSTONE):
	$(MAKE) -C tools/capstone

SRC_AUTO_BIND = $(abspath $(BUILD_SIM_DIR)/auto_bind.cpp)
$(SRC_AUTO_BIND): $(NXDC_FILES)
	python3 $(NVBOARD_HOME)/scripts/auto_pin_bind.py $^ $@

VSRCS = $(shell find $(abspath $(BUILD_SV_DIR)) -name "*.sv" -or -name "*.v")
VSOC = $(shell find "$(SOC_HOME)/perip" -name "*.sv" -or -name "*.v") $(SOC_HOME)/build/ysyxSoCFull.v
# FILELIST := $(shell find $(abspath $(BUILD_SV_DIR)/filelist.f))
CSRCS = $(shell find $(abspath $(SIM_CXX_DIR)) -name "*.cc" -or -name "*.cpp")
DPIC_SRCS = $(shell find $(abspath $(SRC_DPIC_DIR)) -name "*.cc" -or -name "*.cpp")

ifeq ($(WITH_NVBOARD),0)
NVB_ARCHIVE=
LDFLAGS=
else
include $(NVBOARD_HOME)/scripts/nvboard.mk
NVB_ARCHIVE=$(NVBOARD_ARCHIVE)
CSRCS += $(SRC_AUTO_BIND)
endif

$(SIM_BIN): $(CSRCS) $(DPIC_SRCS) $(NVB_ARCHIVE)
	@rm -rf $(BUILD_OBJ_DIR)
	$(VERILATOR) $(VERILATOR_CFLAGS) \
		$(VSRCS) $(VSOC) \
		--top-module $(TOPNAME) \
		$^ $(VINCFLAGS) \
		$(addprefix -CFLAGS , $(CXXFLAGS)) $(addprefix -LDFLAGS , $(LDFLAGS)) \
		--Mdir $(BUILD_OBJ_DIR) --exe -o $(abspath $(SIM_BIN))

test:
	# $(call git_commit, "test RTL") 
	# WARN: Removed this line. 会导致分支混乱且无法恢复.
	mill -i $(PRJ).test.testOnly $(PRJ).test.$(TOPNAME)Spec

runam:
	# $(call git_commit, "run with AM")
	mill -i $(PRJ).test.testOnly $(PRJ).test.$(TOPNAME)Am 

run: verilate verilog $(LIBCAPSTONE) $(SIM_BIN)
	# $(call git_commit, "run with AM via verilator cc")
	@echo "MROMBIN = $(mrombin)"
	$(SIM_BIN) $(mrombin) $(simccargs)

runonly: verilate $(SIM_BIN)
	# $(call git_commit, "run with AM via verilator cc")
	$(SIM_BIN) $(mrombin) $(simccargs)

verilog: $(SCALA_SOURCES)
	$(call git_commit, "generate verilog") # WARN: Only allow tracking here
	mkdir -p $(BUILD_SV_DIR)
	mill -i $(PRJ).runMain Elaborate --target-dir $(BUILD_SV_DIR)
	@echo "Verilog written to " $(BUILD_SV_DIR)
	@echo "Apply memory port changes for SoC"
	@find $(BUILD_SV_DIR) -type f -name "*.v" -o -name "*.sv" -print0 | xargs -0 sed -i -e 's/_\(aw\|ar\|w\|r\|b\)_\(\|bits_\)/_\1/g'
	# @cp $(SRC_DPIC_DIR)/PMemBox.sv $(BUILD_SV_DIR)/PMemBox.sv
	@echo "Memory port renaming done"


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

.PHONY: test runam verilog help reformat checkformat clean verilate sim

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

-include ../Makefile

-include $(PRJ)/MakeTest.mk
