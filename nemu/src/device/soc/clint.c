#include <common.h>
#include <device/map.h>
#include <string.h>
#include <time.h>

// CLINT: Only mtime register at offset 0xbff8 (low) and 0xbffc (high)
#define CLINT_SIZE 0x10000
#define MTIME_OFFSET 0xbff8

static uint8_t *clint_base = NULL;

static uint64_t get_time_us() {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (uint64_t)ts.tv_sec * 1000000 + ts.tv_nsec / 1000;
}

static uint64_t boot_time = 0;

static void clint_io_handler(uint32_t offset, int len, bool is_write) {
  if (!is_write && offset >= MTIME_OFFSET && offset <= MTIME_OFFSET + 4) {
    // Return simulated cycles: host microseconds × 1000 (simulating 1GHz)
    uint64_t mtime = (get_time_us() - boot_time) * 1000;
    uint32_t lo = (uint32_t)(mtime & 0xFFFFFFFF);
    uint32_t hi = (uint32_t)(mtime >> 32);
    memcpy(clint_base + MTIME_OFFSET, &lo, 4);
    memcpy(clint_base + MTIME_OFFSET + 4, &hi, 4);
  }
}

void init_clint() {
  clint_base = new_space(CLINT_SIZE);
  boot_time = get_time_us();
  add_mmio_map("clint", 0x02000000, clint_base, CLINT_SIZE, clint_io_handler);
}
