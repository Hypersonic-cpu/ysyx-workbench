ASMDIR ?= $(PRJ)/prog-gen
HEXDIR ?= $(PRJ)/prog-rom
ROMASM  = $(wildcard $(ASMDIR)/*.S)
ROMHEX  = $(patsubst $(ASMDIR)/%.S, $(HEXDIR)/%.hex, $(ROMASM))
RV64PF := riscv64-linux-gnu-
RVCC   := $(RV64PF)gcc
RVDUMP := $(RV64PF)objdump

.PHONY: genrom

genrom: $(ROMHEX)

$(HEXDIR)/%.hex: $(ASMDIR)/%.asm
	@echo 'Grep-ing hex into >' $@
	@cat $< | sed -E "s/\s+([0-9a-z]+):\s+([0-9a-z]{8})/\2 \/\/ \[__MATCH__\]/g" | grep __MATCH__ > $@

$(ASMDIR)/%.asm: $(ASMDIR)/%.elf
	$(RVDUMP) -d $^ > $@

$(ASMDIR)/%.elf: $(ASMDIR)/%.S
	$(RVCC) -nostdlib -march=rv32i -mabi=ilp32 $^ -o $@
