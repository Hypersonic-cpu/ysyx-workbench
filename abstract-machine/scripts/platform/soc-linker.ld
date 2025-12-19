ENTRY(_start)
/* PHDRS { text PT_LOAD; data PT_LOAD; } */

MEMORY {
  /* mrom : ORIGIN = 0x20000000, LENGTH = 4K */
  flash : ORIGIN = 0x30000000, LENGTH = 256M
  sram : ORIGIN = 0x0f000000, LENGTH = 8K
}

SECTIONS {
  . = ORIGIN(flash);
  .text : {
    *(entry)
    *(.text*)
  } > flash

  .rodata : {
    *(.rodata*)
    _rodata_end = ALIGN(16);
  } > flash

  .data : AT(_rodata_end) {
    _data_beg = .;
    *(.data*)
    *(.sdata*)
    /* TODO: sdata 应该单独成段 */
  } > sram
  edata = .;
  _data = .;
  _data_load = LOADADDR(.data);

  .bss : {
  . = ALIGN(16);
	_bss_start = .;
    *(.bss*)
    *(.sbss*)
    *(.scommon)
    . = ALIGN(16);
  _bss_end = .;
  } > sram
  end = .;
  _end = .;
  _bss_load = LOADADDR(.bss);
  _heap_start = ALIGN(16); /* ALIGN(0x1000); */

  _stack_top = 0x0f001000;
  . = _stack_top + 0x1000;
  _stack_pointer = .;
}

