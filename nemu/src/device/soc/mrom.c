#include "debug.h"
#include "difftest-def.h"
#include <common.h>
#include <device/map.h>
#include <device/mmio.h>

static uint32_t *mrom_data = NULL;

void mrom_listener(uint32_t offset, int len, bool is_write) {
  static bool first_access = true;
  if (!is_write) first_access = false; // first read
  Assert(first_access || !is_write, "Invalid %u bytes write to MROM @ offset " FMT_WORD, len,
         offset);
  Assert(offset % len == 0, "Alignment fault of %u bytes %s @ offset " FMT_WORD,
         len, is_write ? "write" : "read", offset);
}

void init_mrom() {
  mrom_data = (uint32_t *)new_space(CONFIG_SOC_MROM_SIZE);
  add_mmio_map("mrom", CONFIG_SOC_MROM_MMIO, mrom_data, CONFIG_SOC_MROM_SIZE,
               mrom_listener);
}
