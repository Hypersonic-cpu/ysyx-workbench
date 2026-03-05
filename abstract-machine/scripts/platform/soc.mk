AM_SRCS := riscv/soc/start.S \
           riscv/soc/trm.c \
           riscv/soc/ioe.c \
           riscv/soc/timer.c \
           riscv/soc/input.c \
           riscv/soc/cte.c \
           riscv/soc/trap.S \
           platform/dummy/vme.c \
           platform/dummy/mpe.c

CFLAGS    += -fdata-sections -ffunction-sections
LDSCRIPTS += $(AM_HOME)/scripts/platform/soc-linker.ld
LDFLAGS   += --defsym=_pmem_start=0x20000000 --defsym=_entry_offset=0x0
LDFLAGS   += --gc-sections -e _start

# Core clock frequency (MHz). Override with MHZ=<value> on make command line.
MHZ ?= 500
CFLAGS += -DSOC_CYC_PER_US=$(MHZ)

MAINARGS_MAX_LEN = 64
MAINARGS_PLACEHOLDER = the_insert-arg_rule_in_Makefile_will_insert_mainargs_here
CFLAGS += -DMAINARGS_MAX_LEN=$(MAINARGS_MAX_LEN) -DMAINARGS_PLACEHOLDER=$(MAINARGS_PLACEHOLDER)

insert-arg: image
	@python3 $(AM_HOME)/tools/insert-arg.py $(IMAGE).bin $(MAINARGS_MAX_LEN) $(MAINARGS_PLACEHOLDER) "$(mainargs)"

image: image-dep
	@$(OBJDUMP) -d $(IMAGE).elf > $(IMAGE).txt
	@echo + OBJCOPY "->" $(IMAGE_REL).bin
	@$(OBJCOPY) -S \
	  -O binary \
		$(IMAGE).elf $(IMAGE).bin
	@# @$(OBJCOPY) -S --set-section-flags .bss=alloc,contents -O binary $(IMAGE).elf $(IMAGE).bin
	

run: insert-arg cleancc
	@$(MAKE) -C $(NPC_HOME) run SOCMODE=1 mrombin=$(abspath $(IMAGE).bin) MHZ=$(MHZ)

runonly: insert-arg
	@$(MAKE) -C $(NPC_HOME) runonly SOCMODE=1 mrombin=$(abspath $(IMAGE).bin) MHZ=$(MHZ)

buildsv: 
	@$(MAKE) -C $(NPC_HOME) verilog

cleancc: 
	@$(MAKE) -C $(NPC_HOME) clean

.PHONY: insert-arg cleancc buildsv runonly image run
