#include <common.h>
#include <utils.h>

void init_map();
void init_mrom();
void init_sram();
void init_serial();
void init_flash();
void init_clint();

void init_soc() {
  assert(CONFIG_SOC && "Init soc but not configured");
  init_map();
  IFDEF(CONFIG_HAS_MROM, init_mrom());
  IFDEF(CONFIG_HAS_SRAM, init_sram());
  IFDEF(CONFIG_HAS_SERIAL, init_serial());
  IFDEF(CONFIG_HAS_FLASH, init_flash());
  init_clint();
}
