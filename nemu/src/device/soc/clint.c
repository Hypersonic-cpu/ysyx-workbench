#include <common.h>
#include <device/map.h>
#include <device/mmio.h>

// Minimal CLINT for trace generation.
// mtime @ offset 0xBFF8 (addr 0x0200BFF8)

#define CLINT_SIZE  0x10000
#define CLINT_MMIO  0x02000000
#define MTIME_OFS   0xBFF8

static uint8_t *clint_base;
static uint64_t mtime;

static void clint_handler(uint32_t offset, int len, bool is_write) {
  if (!is_write && offset >= MTIME_OFS && offset < MTIME_OFS + 8) {
    mtime++;
    memcpy(clint_base + MTIME_OFS, &mtime, sizeof(mtime));
  }
}

void init_clint() {
  clint_base = new_space(CLINT_SIZE);
  memset(clint_base, 0, CLINT_SIZE);
  add_mmio_map("clint", CLINT_MMIO, clint_base, CLINT_SIZE,
               clint_handler);
}
