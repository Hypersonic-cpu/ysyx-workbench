#include "addrmap.h"
#include "riscv/riscv.h"

#include <am.h>

void __am_timer_init() {
}

void __am_timer_uptime(AM_TIMER_UPTIME_T *uptime) {
  uint32_t hi1 = inl(RV32_SOC_CLOCK + 4);
  uint32_t lo1 = inl(RV32_SOC_CLOCK + 0);
  uint32_t hi2 = inl(RV32_SOC_CLOCK + 4);
  if (hi1 == hi2) {
    uptime->us = hi1;
    uptime->us <<= 32;
    uptime->us |= lo1;
  } else {
    // Overflow on lower 32 bits. Read again and we 
    // believe it's impossible to overflow again.
    lo1 = inl(RV32_SOC_CLOCK + 0);
    uptime->us = hi2;
    uptime->us <<= 32;
    uptime->us |= lo1;
  }
  uptime->us /= SOC_CYC_PER_US;
}

void __am_timer_rtc(AM_TIMER_RTC_T *rtc) {
  rtc->second = 0;
  rtc->minute = 0;
  rtc->hour   = 0;
  rtc->day    = 0;
  rtc->month  = 0;
  rtc->year   = 2025;
}
