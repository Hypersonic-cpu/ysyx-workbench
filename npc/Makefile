## Nv board
include $(NVBOARD_HOME)/scripts/nvboard.mk

TOPNAME = example
NXDC_FIELS = constr/example.nxdc
INC_PATH ?= 

VERILATOR = verilator 
VERILATOR_CFLAGS += -MMD --build -cc
VERILATOR_CFLAGS += -x-assign fast --x-initial fast -O3 -Wall 
# Generate .fst waveform
# VERILATOR_CFLAGS += --trace-fst
# VERILATOR_FLAGS += --assert 
VERILATOR_CFLAGS += --noassert 
# VERILATOR_CFLAGS += --coverage

BUILD_DIR = build
OBJ_DIR = $(BUILD_DIR)/obj_dir
BINARY = $(BUILD_DIR)/$(TOPNAME)
$(shell mkdir -p $(BUILD_DIR))

## Pin binding 
SRC_AUTO_BIND = $(abspath $(BUILD_DIR)/auto_bind.cpp)
$(SRC_AUTO_BIND): $(NXDC_FIELS)
	python3 $(NVBOARD_HOME)/scripts/auto_pin_bind.py $^ $@

## Source files
V_SRC = $(wildcard vsrc/*.v)
C_SRC = $(abspath $(wildcard csrc/*.c csrc/*.cc csrc/*.cxx csrc/*.cpp) )
C_SRC += $(SRC_AUTO_BIND)

## Verilator 
INCFLAGS = $(addprefix -I, $(INC_PATH))
CXXFLAGS += -DTOP_NAME="\"V$(TOPNAME)\""
CXXFLAGS += $(INCFLAGS)

$(BINARY): $(V_SRC) $(C_SRC) $(NVBOARD_ARCHIVE)
	@echo " <= Src V   " $(V_SRC)
	@echo " <= Src CXX " $(C_SRC)
	@echo " => Obj dir " $(OBJ_DIR)
	@echo " INCFLAGS " $(INC_PATH) $(INCFLAGS)
	@rm -rf $(OBJ_DIR)
	$(VERILATOR) $(VERILATOR_CFLAGS) -j 1 \
		-top-module $(TOPNAME) $^ \
		$(addprefix -CFLAGS , $(CXXFLAGS)) $(addprefix -LDFLAGS , $(LDFLAGS)) \
		-Mdir $(OBJ_DIR) --exe -o $(abspath $(BINARY)) 
	@echo "===  Build Done  ==="

## TARGETS ##
.PHONY: sim clean all 

all: $(BINARY) sim

sim:
	@# DO NOT REMOVE THIS LINE!!!
	$(call git_commit, "sim RTL")
	@echo "===  RUNNING " $(BINARY) "  ==="
	./$(BINARY)
	@echo "===  FINISHED  ==="

clean:
	rm -rf $(BUILD_DIR)

