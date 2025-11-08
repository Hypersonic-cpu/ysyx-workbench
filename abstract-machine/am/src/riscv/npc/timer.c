#include "addrmap.h"
#include "riscv/riscv.h"

#include <am.h>

void __am_timer_init() {
}

void __am_timer_uptime(AM_TIMER_UPTIME_T *uptime) {
  uptime->us = inl(RV32_NPC_CLOCK+4); // Higher bits
  uptime->us <<= 32;
  uptime->us |= inl(RV32_NPC_CLOCK);  // Lower bits
}

void __am_timer_rtc(AM_TIMER_RTC_T *rtc) {
  rtc->second = 0;
  rtc->minute = 0;
  rtc->hour   = 0;
  rtc->day    = 0;
  rtc->month  = 0;
  rtc->year   = 2025;
}
