ENTRY(_start)
/* PHDRS { text PT_LOAD; data PT_LOAD; } */

MEMORY {
  /* mrom : ORIGIN = 0x20000000, LENGTH = 4K */
  flash : ORIGIN = 0x30000000, LENGTH = 256M
  sram : ORIGIN = 0x0f000000, LENGTH = 4K
  psram : ORIGIN = 0x80000000, LENGTH = 4M
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

  .boot2 : AT(_boot1_end) {
    _boot2nd_beg = .;
    KEEP(*(boot2nd*))
    . = ALIGN(4);
    _boot2nd_end = .;
  } > psram
  _boot2nd_load = LOADADDR(.boot2);

  .text : AT(_boot2nd_load + SIZEOF(.boot2)) {
    _text_beg = .;
    *(.text*)
    . = ALIGN(16);
    _text_end = .;
  } > psram
  _text_load = LOADADDR(.text);

  .rodata : AT(_text_load + SIZEOF(.text)) {
    _rodata_beg = .;
    *(.rodata*)
    . = ALIGN(16);
    _rodata_end = .;
  } > psram
  _rodata_load = LOADADDR(.rodata);

  .data : AT(_rodata_load + SIZEOF(.rodata)) {
    _data_beg = .;
    *(.data*)
    *(.sdata*)
    . = ALIGN(16);
    /* TODO: sdata 应该单独成段 */
  } > psram
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
  } > psram
  end = .;
  _end = .;
  _bss_load = LOADADDR(.bss);
  _heap_start = ALIGN(16); /* ALIGN(0x1000); */

  _stack_top = 0x0f000000;
  . = _stack_top + 0x2000;
  _stack_pointer = .;
}

