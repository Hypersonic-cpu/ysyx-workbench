#include <am.h>
#include <common.h>
#include <stdio.h>

#if defined(MULTIPROGRAM) && !defined(TIME_SHARING)
# define MULTIPROGRAM_YIELD() yield()
#else
# define MULTIPROGRAM_YIELD()
#endif

#define NAME(key) \
  [AM_KEY_##key] = #key,

static const char *keyname[256] __attribute__((used)) = {
  [AM_KEY_NONE] = "NONE",
  AM_KEYS(NAME)
};

size_t serial_write(const void *buf, size_t offset, size_t len) {
  // Call AM's write
  for (size_t i = 0; i < len; i++) {
    putch(((const char*)buf)[i]);
  }
  return len;
}

size_t events_read(void *buf, size_t offset, size_t len) {
  AM_INPUT_KEYBRD_T ev = {};
  ioe_read(AM_INPUT_KEYBRD, &ev);
  if (ev.keycode == AM_KEY_NONE) {
    return 0;
  }
  int written =
      snprintf(buf, len, "k%c %s\n", ev.keydown ? 'd' : 'u', keyname[ev.keycode]);
  return written < (int)len ? written : len;
}

size_t dispinfo_read(void *buf, size_t offset, size_t len) {
  AM_GPU_CONFIG_T cfg = {};
  ioe_read(AM_GPU_CONFIG, &cfg);
  int written = snprintf(buf, len, "WIDTH: %d\nHEIGHT: %d\n", cfg.width, cfg.height);
  return written < (int)len ? written : len;
}

size_t fb_write(const void *buf, size_t offset, size_t len) {
  AM_GPU_CONFIG_T cfg = {};
  ioe_read(AM_GPU_CONFIG, &cfg);

  size_t pixel_offset = offset / sizeof(uint32_t);
  size_t remain_pixels = len / sizeof(uint32_t);
  size_t row = pixel_offset / cfg.width;
  size_t col = pixel_offset % cfg.width;
  const uint32_t *pixels = (const uint32_t *)buf;

  while (remain_pixels > 0) {
    size_t row_pixels = cfg.width - col;
    if (row_pixels > remain_pixels) {
      row_pixels = remain_pixels;
    }
    AM_GPU_FBDRAW_T draw = {
        .x = col,
        .y = row,
        .pixels = (void *)pixels,
        .w = row_pixels,
        .h = 1,
        .sync = remain_pixels == row_pixels,
    };
    ioe_write(AM_GPU_FBDRAW, &draw);
    pixels += row_pixels;
    remain_pixels -= row_pixels;
    row++;
    col = 0;
  }

  return len;
}

void init_device() {
  Log("Initializing devices...");
  ioe_init();
}
