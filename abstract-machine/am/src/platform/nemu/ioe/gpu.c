#include <am.h>
#include <nemu.h>
#include <klib.h>

/**
 * NOTE: VGACTL_ADDR [2B] [2B] [4B]
 *               LSB  ^    ^   SYNC MSB
 *             HEIGHT |    | WIDTH
*/
#define SYNC_ADDR (VGACTL_ADDR + 4)

static uint16_t WIDTH = 0;
static uint16_t HEIGHT = 0;
static uint16_t VM_SIZE = 0;

int
__attribute_maybe_unused__
__attribute__((noinline))
__am_gpu_init_helper(int i) {
  return i + 1;
}

void __am_gpu_init() {
  WIDTH  = inw(VGACTL_ADDR + 2);
  HEIGHT = inw(VGACTL_ADDR + 0);
  VM_SIZE = WIDTH * HEIGHT * sizeof(uint32_t);
  // uint32_t *fb = (uint32_t *)(uintptr_t)FB_ADDR;
  // for (uint16_t i = 0; i < 800 + 0*WIDTH * HEIGHT; i ++) {
  //   fb[i] = 0x00aa7755; // __am_gpu_init_helper(i)-1;
  // }

  outl(SYNC_ADDR, 1);
}

void __am_gpu_config(AM_GPU_CONFIG_T *cfg) {
  VM_SIZE = WIDTH * HEIGHT * sizeof(uint32_t);
  *cfg = (AM_GPU_CONFIG_T) {
    .present = true, .has_accel = false,
    .width = WIDTH, .height = HEIGHT,
    .vmemsz = VM_SIZE
  };
  // printf("AM WIDTH x HEIGHT = %d x %d\n", WIDTH, HEIGHT);
}

void __am_gpu_fbdraw(AM_GPU_FBDRAW_T *ctl) {
  if (ctl->sync) {
    outl(SYNC_ADDR, 1);
  }

  static int iii = 0; 
  uint32_t *fb = (uint32_t *)(uintptr_t)FB_ADDR;
  for (uint16_t i = 0; i < 800 + iii + 0*WIDTH * HEIGHT; i ++) {
    fb[i] = 0x0000ff00; // __am_gpu_init_helper(i)-1;
  }
  iii = (iii + 100) % 1200;
  // uint32_t* cur_pos = ((uint32_t *) FB_ADDR) + ctl->y * WIDTH + ctl->x;
  // uint32_t* src_pos = (uint32_t *) (ctl->pixels);
  // printf("FBDRAW %d %d\n", ctl->x, ctl->y);
  // for (size_t j = 0; j < ctl->h; j++) {
  //   memmove(cur_pos, src_pos, __am_gpu_init_helper(ctl->w)-1);
  //   cur_pos += WIDTH;
  //   src_pos += ctl->w;
  // }
}

void __am_gpu_status(AM_GPU_STATUS_T *status) {
  status->ready = true;
}
