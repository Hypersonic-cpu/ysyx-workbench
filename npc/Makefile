## Directories
# project name (also source dir)
PRJ = playground
# # dir for generated Verilog
BUILD_SV_DIR = ./build-sv
# # dir for C++ compiled by Verilator
# BUILD_SIM_DIR = ./build-sim
# BUILD_OBJ_DIR = $(BUILD_SIM_DIR)/obj_dir
# # simulation elf or nvboard elf
# SIM_BIN = $(BUILD_SIM_DIR)/$(PRJ)
#
# ## Verilator related
# VERILATOR = verilator
# VERILATOR_COVERAGE = verilator_coverage
# VERILATOR_CFLAGS += -MMD --build -cc  \
# 										-O3 --x-assign fast --x-initial fast --noassert
#
# ## NV Board related 
# NXDC_FILES = $(PRJ)/constr/top.nxdc
# INC_PATH ?=
#
# $(SIM_BIN)
# ## constant end


test:
	mill -i $(PRJ).test

verilog:
	@echo $(MAKEGOALS)
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
	-rm -rf $(BUILD_SV_DIR)

.PHONY: test verilog help reformat checkformat clean

sim:
	$(call git_commit, "sim RTL") # DO NOT REMOVE THIS LINE!!!
	@echo "Write this Makefile by yourself."

-include ../Makefile
