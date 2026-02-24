#include <am.h>
#include <klib-macros.h>
#include <klib.h>
#include <stdint.h>

#include "addrmap.h"

int main(const char *args);

extern char _heap_start;
#define HEAP_SIZE (1 << 20)
#define HEAP_END ((uintptr_t)&_heap_start + HEAP_SIZE)

Area heap = RANGE(&_heap_start, HEAP_END);
static const char mainargs[MAINARGS_MAX_LEN] =
    TOSTRING(MAINARGS_PLACEHOLDER); // defined in CFLAGS

void uart_init() {
  // TODO: Set proper clock rate
  uint16_t const divisor = 1; // 10 * 1000000 / UART_BAUD_RATE / 16;
  // Enable divisor IO
  uint8_t tmp_lcr = *(volatile uint8_t *)(RV32_SOC_UART_L + UART_OFF_LCR);
  tmp_lcr |= 0x80U;
  *(volatile uint8_t *)(RV32_SOC_UART_L + UART_OFF_LCR) = tmp_lcr;
  // Divisors
  *(volatile uint8_t *)(RV32_SOC_UART_L + UART_OFF_DIV + 0) = divisor & 0xffU;
  *(volatile uint8_t *)(RV32_SOC_UART_L + UART_OFF_DIV + 1) = divisor >> 8;
  // Disable divisor IO
  tmp_lcr &= 0x7fU;
  *(volatile uint8_t *)(RV32_SOC_UART_L + UART_OFF_LCR) = tmp_lcr;
}

void putch(char ch) {
  // If (not empty) then wait
  // uint8_t
  register unsigned ls = *(volatile uint8_t *)(RV32_SOC_UART_L + UART_OFF_LS);
  while (!(ls & 0x40)) {
    ls = *(volatile uint8_t *)(RV32_SOC_UART_L + UART_OFF_LS);
    asm volatile("addi x5, %0, 0" ::"r"(ls) : "x5");
  }
  *(volatile uint8_t *)(RV32_SOC_UART_L + UART_OFF_THR) = ch;
}

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
  uart_init();
  int ret = main(mainargs);
  halt(ret);
}
