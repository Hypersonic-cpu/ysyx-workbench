#include <am.h>
#include <nemu.h>

extern char _heap_start;
int main(const char *args);

Area heap = RANGE(&_heap_start, PMEM_END);
static const char mainargs[MAINARGS_MAX_LEN] = TOSTRING(MAINARGS_PLACEHOLDER); // defined in CFLAGS

void putch(char ch) {
  outb(SERIAL_PORT, ch);
}

void halt(int code) {
  nemu_trap(code);

  // should not reach here
  while (1);
}

void _trm_init() {
  int ret = main(mainargs);
  halt(ret);
}

void cc_reset_stats() {
  asm volatile("li x15, 0x0" ::: "x15", "memory");
  asm volatile("ebreak");
}

void cc_dump_stats() {
  asm volatile("li x15, 0x1" ::: "x15", "memory");
  asm volatile("ebreak");
}
