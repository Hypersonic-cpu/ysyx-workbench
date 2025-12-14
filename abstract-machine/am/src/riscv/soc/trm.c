#include <am.h>
#include <klib-macros.h>

#include "addrmap.h"

int main(const char *args);

// PSRAM         0x8000_0000 ~ 0x9fff_ffff
// SDRAM         0xa000_0000 ~ 0xbfff_ffff
extern char _pmem_start;
#define PMEM_SIZE (128 << 20)
#define PMEM_END  ((uintptr_t)&_pmem_start + PMEM_SIZE)

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
  int ret = main(mainargs);
  halt(ret);
}
