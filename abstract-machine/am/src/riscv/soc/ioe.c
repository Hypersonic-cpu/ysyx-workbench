#include <am.h>
#include <klib.h>
#include <klib-macros.h>
#include <stdint.h>
#include "addrmap.h"
#include "riscv/riscv.h"

void __am_timer_init();

void __am_timer_rtc(AM_TIMER_RTC_T *);
void __am_timer_uptime(AM_TIMER_UPTIME_T *);
void __am_input_keybrd(AM_INPUT_KEYBRD_T *);

static void __am_timer_config(AM_TIMER_CONFIG_T *cfg) { cfg->present = true; cfg->has_rtc = true; }
static void __am_input_config(AM_INPUT_CONFIG_T *cfg) { cfg->present = true;  }
static void __am_uart_config(AM_INPUT_CONFIG_T *cfg) { cfg->present = false;  }

static void __am_uart_rx(AM_UART_RX_T *r) { 
  int line_status = inb(RV32_SOC_UART_L+UART_OFF_LS);
  if (line_status & 1) {
    r->data = inb(RV32_SOC_UART_L);
  } else {
    r->data = 0xffU;
  }
}

#define VGA_WIDTH  640
#define VGA_HEIGHT 480

static void __am_gpu_config(AM_GPU_CONFIG_T *cfg) {
  cfg->has_accel = false;
  cfg->present = true;
  cfg->vmemsz = VGA_HEIGHT * VGA_WIDTH * 4;
  cfg->height = VGA_HEIGHT;
  cfg->width = VGA_WIDTH;
}
static void __am_gpu_fbdraw(AM_GPU_FBDRAW_T *ctl) {
  uint32_t* cur_pos = 
    ((uint32_t *)(uintptr_t) RV32_SOC_VGAMEM) 
      + ctl->y * VGA_WIDTH + ctl->x;
  uint32_t* src_pos = (uint32_t *) (ctl->pixels);
  if (!src_pos) return;
  for (size_t j = 0; j < ctl->h; j++) {
    memcpy(cur_pos, src_pos, ctl->w * sizeof(uint32_t));
    cur_pos += VGA_WIDTH;
    src_pos += ctl->w;
  }
}

typedef void (*handler_t)(void *buf);
static void *lut[128] = {
  [AM_TIMER_CONFIG] = __am_timer_config,
  [AM_TIMER_RTC   ] = __am_timer_rtc,
  [AM_TIMER_UPTIME] = __am_timer_uptime,
  [AM_INPUT_CONFIG] = __am_input_config,
  [AM_INPUT_KEYBRD] = __am_input_keybrd,
  [AM_UART_CONFIG ] = __am_uart_config,
  [AM_UART_RX     ] = __am_uart_rx,
  [AM_GPU_CONFIG  ] = __am_gpu_config,
  [AM_GPU_FBDRAW  ] = __am_gpu_fbdraw,
};

static void fail(void *buf) { panic("access nonexist register"); }

bool ioe_init() {
  for (int i = 0; i < LENGTH(lut); i++)
    if (!lut[i]) lut[i] = fail;
  __am_timer_init();
  return true;
}

void ioe_read (int reg, void *buf) { ((handler_t)lut[reg])(buf); }
void ioe_write(int reg, void *buf) { ((handler_t)lut[reg])(buf); }
