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
INC_PATH ?=

## Verilator related
VERILATOR = verilator
VERILATOR_COVERAGE = verilator_coverage
VERILATOR_TRACE ?= --trace --trace-fst --trace-depth 99
VERILATOR_CFLAGS += $(VERILATOR_TRACE)
VERILATOR_CFLAGS += -MMD --build -cc  \
										-O3 --x-assign fast --x-initial fast --noassert
INCFLAGS = $(addprefix -I, $(INC_PATH))
CXXFLAGS += $(INCFLAGS) -DTOP_NAME="\"V$(TOPNAME)\"" -DPRINTF_COND=0

SRC_AUTO_BIND = $(abspath $(BUILD_SIM_DIR)/auto_bind.cpp)
$(SRC_AUTO_BIND): $(NXDC_FILES)
	python3 $(NVBOARD_HOME)/scripts/auto_pin_bind.py $^ $@

VSRCS = $(shell find $(abspath $(BUILD_SV_DIR)) -name "*.sv" -or -name "*.v")
CSRCS = $(shell find $(abspath $(SIM_CXX_DIR)) -name "*.cc" -or -name "*.cpp")
DPIC_SRCS = $(shell find $(abspath $(SRC_DPIC_DIR)) -name "*.cc" -or -name "*.cpp")

ifeq ($(WITH_NVBOARD),0)
NVB_ARCHIVE=
# LDFLAGS=
else
include $(NVBOARD_HOME)/scripts/nvboard.mk
NVB_ARCHIVE=$(NVBOARD_ARCHIVE)
CSRCS += $(SRC_AUTO_BIND)
endif

$(SIM_BIN): $(VSRCS) $(CSRCS) $(NVB_ARCHIVE)
	@rm -rf $(BUILD_OBJ_DIR)
	$(VERILATOR) $(VERILATOR_CFLAGS) \
		--top-module $(TOPNAME) $^ $(DPIC_SRCS) \
		$(addprefix -CFLAGS , $(CXXFLAGS)) $(addprefix -LDFLAGS , $(LDFLAGS)) \
		--Mdir $(BUILD_OBJ_DIR) --exe -o $(abspath $(SIM_BIN))

test:
	$(call git_commit, "test RTL") # DO NOT REMOVE THIS LINE!!!
	mill -i $(PRJ).test.testOnly $(PRJ).test.$(TOPNAME)Spec

runam:
	$(call git_commit, "run with AM")
	mill -i $(PRJ).test.testOnly $(PRJ).test.$(TOPNAME)Am 

runcc: verilate verilog $(SIM_BIN)
	$(call git_commit, "run with AM via verilator cc")
	$(SIM_BIN)

verilog:
	$(call git_commit, "generate verilog")
	mkdir -p $(BUILD_SV_DIR)
	mill -i $(PRJ).runMain Elaborate --target-dir $(BUILD_SV_DIR)

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

-include ../Makefile

-include $(PRJ)/MakeTest.mk
