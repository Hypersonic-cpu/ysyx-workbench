#include <common.h>
#include <utils.h>

void init_mrom();
void init_sram();
void init_flash();

void init_soc() {
  IFDEF(CONFIG_HAS_MROM, init_mrom());
  IFDEF(CONFIG_HAS_SRAM, init_sram());
  IFDEF(CONFIG_HAS_FLASH, init_flash());
}
