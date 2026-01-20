#include <am.h>
#include <klib.h>
#include <klib-macros.h>

#include "addrmap.h"

extern char _heap_start;
int main(const char *args);

extern char _pmem_start;
#define PMEM_SIZE (128 * 1024 * 1024)
#define PMEM_END ((uintptr_t)&_pmem_start + PMEM_SIZE)

Area heap = RANGE(&_heap_start, PMEM_END);
static const char mainargs[MAINARGS_MAX_LEN] =
    TOSTRING(MAINARGS_PLACEHOLDER); // defined in CFLAGS

void putch(char ch) { *((volatile uint8_t *)RV32_NPC_SERIAL) = ch; }

void halt(int code) {
  asm volatile("li x15, 0xaa" ::: "x15", "memory");
  asm volatile("ebreak");
  while (1)
    ;
}

void cc_reset_stats() {
  asm volatile("li x15, 0x0" ::: "x15", "memory");
  asm volatile("ebreak");
}

void cc_dump_stats() {
  asm volatile("li x15, 0x1" ::: "x15", "memory");
  asm volatile("ebreak");
}

void _trm_init() {
  cc_reset_stats();
  int ret = main(mainargs);
  halt(ret);
}
