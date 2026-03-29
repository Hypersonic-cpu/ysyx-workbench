#include <am.h>
#include <nemu.h>
#include <stdio.h>

void __am_timer_init() {}

void __am_timer_uptime(AM_TIMER_UPTIME_T *uptime) {
  uint32_t time_hi = inl(RTC_ADDR + sizeof(uint32_t));
  uint32_t time_lo = inl(RTC_ADDR);
  uptime->us = time_hi;
  uptime->us <<= 32;
  uptime->us |= time_lo;
}

void __am_timer_rtc(AM_TIMER_RTC_T *rtc) {
  rtc->second = 0;
  rtc->minute = 0;
  rtc->hour = 0;
  rtc->day = 0;
  rtc->month = 0;
  rtc->year = 1900;
}
