#include <am.h>
#include <klib-macros.h>
#include <klib.h>
#include <stdint.h>

#include "addrmap.h"

int main(const char *args);

extern char _pmem_start;
#define PMEM_SIZE (128 << 20)
#define PMEM_END ((uintptr_t)&_pmem_start + PMEM_SIZE)

extern char _bss_start, _bss_end, _bss_load;
extern char _data_beg, _data, _data_load;

extern char _heap_start;
Area heap = RANGE(&_heap_start, PMEM_END);
static const char mainargs[MAINARGS_MAX_LEN] =
    TOSTRING(MAINARGS_PLACEHOLDER); // defined in CFLAGS

void uart_init() {
  // TODO: Set proper clock rate
  uint16_t const divisor = 10 * 1000000 / UART_BAUD_RATE / 16;
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
  // asm volatile ("nop; nop; nop; nop; nop");
  // asm volatile ("ebreak");
}

void putch(char ch) {
  // If (not empty) then wait
  // uint8_t 
  register unsigned ls = *(volatile uint8_t *)(RV32_SOC_UART_L + UART_OFF_LS);
  while (!(ls & 0x40)) {
    ls = *(volatile uint8_t *)(RV32_SOC_UART_L + UART_OFF_LS);
    asm volatile ("addi x5, %0, 0" ::"r"(ls) : "x5");
  }
  *(volatile uint8_t *)(RV32_SOC_UART_L + UART_OFF_THR) = ch;
}

void halt(int code) {
  asm volatile("ebreak");
  while (1)
    ;
}

void _trm_init() {
  uint32_t data_size = &_data - &_data_beg;
  memcpy(&_data_beg, &_data_load, data_size);
  uint32_t bss_size = &_bss_end - &_bss_start;
  memset(&_bss_start, 0, bss_size);
  uart_init();

  int ret = main(mainargs);
  halt(ret);
}
