#include <am.h>
#include <common.h>
#include <stdio.h>

#ifndef EVENT_SCRIPT
#define EVENT_SCRIPT ""
#endif

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

static const char *script_cursor = NULL;

static size_t scripted_event_read(void *buf, size_t len) {
  if (script_cursor == NULL) {
    script_cursor = EVENT_SCRIPT;
  }
  while (*script_cursor == ' ' || *script_cursor == '\t' ||
         *script_cursor == '\n' || *script_cursor == ';') {
    script_cursor++;
  }
  if (*script_cursor == '\0') {
    return 0;
  }

  char event[64];
  int n = 0;
  while (script_cursor[n] != '\0' && script_cursor[n] != ';' &&
         script_cursor[n] != '\n' && n < (int)sizeof(event) - 1) {
    event[n] = script_cursor[n];
    n++;
  }
  while (n > 0 && (event[n - 1] == ' ' || event[n - 1] == '\t')) {
    n--;
  }
  event[n] = '\0';
  script_cursor += n;
  while (*script_cursor == ' ' || *script_cursor == '\t' ||
         *script_cursor == '\n' || *script_cursor == ';') {
    script_cursor++;
  }

  int written = snprintf(buf, len, "%s\n", event);
  return written < (int)len ? written : len;
}

size_t serial_write(const void *buf, size_t offset, size_t len) {
  yield();
  // Call AM's write
  for (size_t i = 0; i < len; i++) {
    putch(((const char*)buf)[i]);
  }
  return len;
}

size_t events_read(void *buf, size_t offset, size_t len) {
  yield();
  size_t scripted = scripted_event_read(buf, len);
  if (scripted > 0) {
    return scripted;
  }
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
  yield();
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
