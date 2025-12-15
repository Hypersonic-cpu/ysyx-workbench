#include <am.h>
#include <klib.h>
#include <klib-macros.h>

#include "addrmap.h"

int main(const char *args);

extern char _pmem_start;
#define PMEM_SIZE (128 << 20)
#define PMEM_END  ((uintptr_t)&_pmem_start + PMEM_SIZE)

extern char _bss_start, _bss_end, _bss_load;
extern char _data_beg, _data, _data_load;

extern char _heap_start;
Area heap = RANGE(&_heap_start, PMEM_END);
static const char mainargs[MAINARGS_MAX_LEN] = TOSTRING(MAINARGS_PLACEHOLDER); // defined in CFLAGS

void putch(char ch) {
  *(volatile uint8_t* )(RV32_SOC_UART_L + 0) = ch;
}

void halt(int code) {
  asm volatile ("ebreak");
  while (1);
}

void _trm_init() {
  uint32_t data_size = &_data - &_data_beg;
  memcpy(&_data_beg, &_data_load , data_size);
  uint32_t bss_size = &_bss_end - &_bss_start;
  memset(&_bss_start, 0, bss_size);

  int ret = main(mainargs);
  halt(ret);
}
