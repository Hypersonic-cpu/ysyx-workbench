VERILATOR = verilator 

BUILD_CXX_FLAGS = -cc 
BUILD_ELF_FLAGS = -cc --exe --build

VERILATOR_FLAGS += -x-assign fast -Wall 
# Generate .fst waveform
VERILATOR_FLAGS += --trace-fst
VERILATOR_FLAGS += --assert 
VERILATOR_FLAGS += --coverage

OBJ_DIR = obj_dir
SOURCE_V = $(wildcard vsrc/*.v)
WRAPPER_CXX = csrc/main.cpp
ELF_NAME = Vexample
ELF_PATH = $(OBJ_DIR)/$(ELF_NAME)

.PHONY: build_cxx build_elf sim

default: all

all: build_elf sim

build_elf:
	@echo "  ===  VERILATE  ===  "
	@echo "  <= source files " $(SOURCE_V)
	@echo "  <= main() cxx " $(WRAPPER_CXX)
	@echo "  => elf path " $(ELF_PATH)
	@mkdir -p $(OBJ_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) $(BUILD_ELF_FLAGS) -j 0 \
		-Mdir $(OBJ_DIR) -o $(ELF_NAME) \
		$(SOURCE_V) $(WRAPPER_CXX) 
	@echo "  ===  BUILD ELF DONE  ===  "

build_cxx:
	@echo "  ===  VERILATE  ===  "
	@echo "  <= source files " $(SOURCE_V)
	@echo "  <= main() cxx " $(WRAPPER_CXX)
	@mkdir -p $(OBJ_DIR)
	$(VERILATOR) $(VERILATOR_FLAGS) $(BUILD_CXX_FLAGS) -j 0 \
		-Mdir $(OBJ_DIR) -o $(ELF_NAME) $(SOURCE_V)
	@echo "  ===  BUILD CXX DONE  ===  "

sim:
	$(call git_commit, "sim RTL") # DO NOT REMOVE THIS LINE!!!
	@echo "  ===  RUNNING ===  "
	./$(ELF_PATH) +trace 
	@echo "  ===  FINISHED  ===  "

include ../Makefile

