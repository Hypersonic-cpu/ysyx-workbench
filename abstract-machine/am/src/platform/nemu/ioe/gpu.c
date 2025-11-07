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
  uint32_t *fb = (uint32_t *)(uintptr_t)FB_ADDR;
  for (uint16_t i = 300; i < 800 + 0*WIDTH * HEIGHT; i ++) {
    fb[i] = 0x00aa7755; // __am_gpu_init_helper(i)-1;
  }

  outl(SYNC_ADDR, 1);
  // printf("AM WIDTH x HEIGHT = %d x %d\n", WIDTH, HEIGHT);
  // while (1);
}

void __am_gpu_config(AM_GPU_CONFIG_T *cfg) {
  __am_gpu_init();
  int VMSIZE = WIDTH * HEIGHT * sizeof(uint32_t);
  *cfg = (AM_GPU_CONFIG_T) {
    .present = true, .has_accel = false,
    .width = WIDTH, .height = HEIGHT,
    .vmemsz = VMSIZE
  };
  printf("AM WIDTH x HEIGHT = %d x %d\n", WIDTH, HEIGHT);
}

void __am_gpu_fbdraw(AM_GPU_FBDRAW_T *ctl) {
  if (ctl->sync) {
    outl(SYNC_ADDR, 1);
  }
}

void __am_gpu_status(AM_GPU_STATUS_T *status) {
  status->ready = true;
}
