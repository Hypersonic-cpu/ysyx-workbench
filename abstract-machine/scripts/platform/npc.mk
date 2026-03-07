AM_SRCS := riscv/npc/start.S \
           riscv/npc/trm.c \
           riscv/npc/ioe.c \
           riscv/npc/timer.c \
           riscv/npc/input.c \
           riscv/npc/cte.c \
           riscv/npc/trap.S \
           platform/dummy/vme.c \
           platform/dummy/mpe.c

CFLAGS    += -fdata-sections -ffunction-sections
LDSCRIPTS += $(AM_HOME)/scripts/linker.ld
LDFLAGS   += --defsym=_pmem_start=0x80000000 --defsym=_entry_offset=0x0
LDFLAGS   += --gc-sections -e _start

# Core clock frequency (MHz). Override with MHZ=<value> on make command line.
MHZ ?= 1000
export MHZ
CFLAGS += -DNPC_CYC_PER_US=$(MHZ)

# Rebuild AM objects when MHZ changes (NPC_CYC_PER_US is a compile-time constant)
NPC_MHZ_STAMP := $(WORK_DIR)/.npc_mhz$(MHZ).stamp
$(NPC_MHZ_STAMP):
	@rm -f $(WORK_DIR)/.npc_mhz*.stamp
	@rm -rf $(DST_DIR) $(AM_HOME)/am/build/$(ARCH)
	@touch $@

MAINARGS_MAX_LEN = 64
MAINARGS_PLACEHOLDER = the_insert-arg_rule_in_Makefile_will_insert_mainargs_here
CFLAGS += -DMAINARGS_MAX_LEN=$(MAINARGS_MAX_LEN) -DMAINARGS_PLACEHOLDER=$(MAINARGS_PLACEHOLDER)

insert-arg: image
	@python3 $(AM_HOME)/tools/insert-arg.py $(IMAGE).bin $(MAINARGS_MAX_LEN) $(MAINARGS_PLACEHOLDER) "$(mainargs)"

image: $(NPC_MHZ_STAMP) image-dep
	@$(OBJDUMP) -d $(IMAGE).elf > $(IMAGE).txt
	@echo + OBJCOPY "->" $(IMAGE_REL).bin
	@$(OBJCOPY) -S --set-section-flags .bss=alloc,contents -O binary $(IMAGE).elf $(IMAGE).bin

runam: insert-arg
	@$(MAKE) -C $(NPC_HOME) runam

run: insert-arg cleancc
	@$(MAKE) -C $(NPC_HOME) run SOCMODE=0 imagebin=$(abspath $(IMAGE).bin) MHZ=$(MHZ)

runonly: insert-arg
	@$(MAKE) -C $(NPC_HOME) runonly SOCMODE=0 imagebin=$(abspath $(IMAGE).bin) MHZ=$(MHZ)

buildsv: 
	@$(MAKE) -C $(NPC_HOME) verilog

cleancc: 
	@$(MAKE) -C $(NPC_HOME) clean

.PHONY: insert-arg cleancc buildsv runonly
