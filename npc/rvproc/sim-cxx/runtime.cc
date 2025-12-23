#include "runtime.hh"
#include <cassert>
#include <cstdint>
#include <memory>

const RuntimeBin* mrom = nullptr;
const RuntimeBin* flash = nullptr;
RuntimeBin* psram = nullptr;

void
mrom_read(int32_t addr, int32_t* data) {
  *(uint32_t*)data = mrom->readAligned(addr);
}

void
flash_read(int32_t addr, int32_t* data) {
  *(uint32_t*)data = flash->readAligned(addr);
  // std::cerr << std::hex;
  // std::cerr << "DPI-C flash read @ " << addr << " data = " << *data
  //           << std::endl;
}

uint8_t
psram_read(uint32_t addr) {
  // std::cerr << std::hex;
  // std::cerr << "DPI-C psram read @ " << addr << " data = " << psram->readAligned(addr)
  //           << std::endl;
  return psram->readByte(addr);
}

void
psram_write(uint32_t addr, unsigned char data) {
  // std::cerr << std::hex;
  // std::cerr << "DPI-C psram write @ " << addr << " data = " << (uint16_t) data
  //           << std::endl;
  psram->writeByte(addr, data);
}
