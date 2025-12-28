ENTRY(_start)
/* PHDRS { text PT_LOAD; data PT_LOAD; } */

MEMORY {
  /* mrom : ORIGIN = 0x20000000, LENGTH = 4K */
  flash : ORIGIN = 0x30000000, LENGTH = 256M
  sram  : ORIGIN = 0x0f000000, LENGTH = 4K
  psram : ORIGIN = 0x80000000, LENGTH = 4M
  sdram : ORIGIN = 0xa0000000, LENGTH = 32M
}

SECTIONS {
  . = ORIGIN(flash);
  .boot1 : {
    _boot1_load = .;
    _boot1_beg = .;
    KEEP(*(boot1st*))
    . = ALIGN(4);
    _boot1_end = .;
  } > flash

  .boot2 : {
    _boot2nd_beg = .;
    KEEP(*(boot2nd*))
    . = ALIGN(4);
    _boot2nd_end = .;
  } > sdram AT> flash
  _boot2nd_load = LOADADDR(.boot2);

  .text : {
    _text_beg = .;
    *(.text*)
    . = ALIGN(16);
    _text_end = .;
  } > sdram AT> flash
  _text_load = LOADADDR(.text);

  .rodata : {
    _rodata_beg = .;
    *(.rodata*)
    . = ALIGN(16);
    _rodata_end = .;
  } > sdram AT> flash
  _rodata_load = LOADADDR(.rodata);

  .data : {
    _data_beg = .;
    *(.data*)
    *(.sdata*)
    . = ALIGN(16);
    /* TODO: sdata 应该单独成段 */
  } > sdram AT> flash
  edata = .;
  _data = .;
  _data_end = .;
  _data_load = LOADADDR(.data);

  .bss : {
	  _bss_start = .;
    *(.bss*)
    *(.sbss*)
    *(.scommon)
    . = ALIGN(16);
    _bss_end = .;
  } > sdram
  end = .;
  _end = .;
  _bss_load = LOADADDR(.bss);
  _heap_start = ALIGN(16); /* ALIGN(0x1000); */

  _stack_top = 0x0f000000;
  . = _stack_top + 0x2000;
  /* _stack_top = 0x80200000; */
  /* . = _stack_top + 0x100000; */
  /* _stack_top = 0xbc000000; */
  /* . = _stack_top + 0x4000000; */
  _stack_pointer = .;
}

