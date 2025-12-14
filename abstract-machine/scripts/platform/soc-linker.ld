ENTRY(_start)
PHDRS { text PT_LOAD; data PT_LOAD; }

SECTIONS {
  _stack_top = 0x0f000000;
  . = _stack_top + 0x2000;
  _stack_pointer = .;
  /* _pmem_start and _entry_offset are defined in LDFLAGS */
  . = _pmem_start + _entry_offset;
  .text : {
    *(entry)
    *(.text*)
  } : text
  etext = .;
  _etext = .;
  .rodata : {
    *(.rodata*)
  }
  .data : {
    *(.data*)
  } : data
  edata = .;
  _data = .;
  .bss : {
	_bss_start = .;
    *(.bss*)
    *(.sbss*)
    *(.scommon)
  }
  _heap_start = ALIGN(0x1000);
  end = .;
  _end = .;
}
