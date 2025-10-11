BUILD_DIR = ./build

PRJ = playground

## Verilator related
# VERILATOR = verilator
# VERILATOR_COVERAGE = verilator_coverage
#
# VERILATOR_CFLAGS += -MMD --build -cc  \
# 				-O3 --x-assign fast --x-initial fast --noassert

## NV Board related 

# TOPNAME = top
# NXDC_FILES = constr/top.nxdc
# INC_PATH ?=
#
# VERILATOR = verilator
#
# NVB_BUILD_DIR = ./build/
# OBJ_DIR = $(BUILD_DIR)/obj_dir
# BIN = $(BUILD_DIR)/$(TOPNAME)

##< NVBoard end


test:
	mill -i $(PRJ).test

verilog:
	$(call git_commit, "generate verilog")
	mkdir -p $(BUILD_DIR)
	mill -i $(PRJ).runMain Elaborate --target-dir $(BUILD_DIR)

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
	-rm -rf $(BUILD_DIR)

.PHONY: test verilog help reformat checkformat clean

sim:
	$(call git_commit, "sim RTL") # DO NOT REMOVE THIS LINE!!!
	@echo "Write this Makefile by yourself."

-include ../Makefile
