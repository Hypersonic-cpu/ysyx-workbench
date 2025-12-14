#include "debug.h"
#include <common.h>
#include <device/map.h>
#include <device/mmio.h>

static uint32_t *sram_data = NULL;

void sram_listener(uint32_t offset, int len, bool is_write) {
  Assert(offset % len == 0, "Alignment fault of %u bytes %s @ offset " FMT_WORD,
         len, is_write ? "write" : "read", offset);
  // printf("[  SOC  ] %s to SRAM!! offset = " FMT_WORD " len = %u\n",
  //        is_write ? "write" : "read", offset, len);
}

void init_sram() {
  sram_data = (uint32_t *)new_space(CONFIG_SOC_SRAM_SIZE);
  add_mmio_map("sram", CONFIG_SOC_SRAM_MMIO, sram_data, CONFIG_SOC_SRAM_SIZE,
               sram_listener);
}
