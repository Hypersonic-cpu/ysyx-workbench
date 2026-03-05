#include "debug.h"
#include <common.h>
#include <device/map.h>
#include <device/mmio.h>

static uint32_t *flash_data = NULL;

void flash_listener(uint32_t offset, int len, bool is_write) {
  Assert(offset % len == 0, "Alignment fault of %u bytes %s @ offset " FMT_WORD,
         len, is_write ? "write" : "read", offset);
  // printf("[  SOC  ] %s to FLASH! offset = " FMT_WORD " len = %u\n",
  //        is_write ? "write" : "read", offset, len);
}

void init_flash() {
  flash_data = (uint32_t *)new_space(CONFIG_SOC_FLASH_SIZE);
  add_mmio_map("flash", CONFIG_SOC_FLASH_MMIO, flash_data, CONFIG_SOC_FLASH_SIZE,
               flash_listener);
}
