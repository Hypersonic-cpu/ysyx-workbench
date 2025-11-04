ASMDIR ?= $(PRJ)/prog-gen
HEXDIR ?= $(PRJ)/prog-rom
ROMASM  = $(wildcard $(ASMDIR)/*.S)
ROMHV3  = $(wildcard $(ASMDIR)/*.hexv3)
ROMHEX := $(patsubst $(ASMDIR)/%.S, $(HEXDIR)/%.hex, $(ROMASM))
ROMHEX += $(patsubst $(ASMDIR)/%.hexv3, $(HEXDIR)/%_v3.hex, $(ROMHV3))
RV64PF := riscv64-linux-gnu-
RVCC   := $(RV64PF)gcc
RVDUMP := $(RV64PF)objdump

.PHONY: genrom

genrom: $(ROMHEX)

$(HEXDIR)/%_v3.hex: $(ASMDIR)/%.hexv3
	@cat $< | tail -n +2 | sed -E 's/^[0-9a-zA-Z]+: //' | sed -E 's/ /\n/g' > $@


$(HEXDIR)/%.hex: $(ASMDIR)/%.asm
	@echo 'Grep-ing hex into >' $@
	@cat $< | sed -E "s/\s+([0-9a-z]+):\s+([0-9a-z]{8})/\2 \/\/ \[__MATCH__\]/g" | grep __MATCH__ > $@

$(ASMDIR)/%.asm: $(ASMDIR)/%.elf
	$(RVDUMP) -d $^ > $@

$(ASMDIR)/%.elf: $(ASMDIR)/%.S
	$(RVCC) -O0 -g -nostdlib -march=rv32i -mabi=ilp32 $^ -o $@
