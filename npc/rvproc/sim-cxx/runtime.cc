#include "runtime.hh"
#include <cassert>
#include <cstdint>
#include <memory>

const RuntimeBin* mrom = nullptr;
const RuntimeBin* flash = nullptr;
RuntimeBin* psram = nullptr;
RuntimeBin* sdram = nullptr;

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
  // std::cerr << "DPI-C psram read @ " << addr << " data = " <<
  // psram->readByte(addr)
  //           << std::endl;
  return psram->readByte(addr);
}

void
psram_write(uint32_t addr, unsigned char data) {
  // std::cerr << std::hex;
  // std::cerr << "DPI-C psram write @ " << addr << " data = " << (uint16_t)
  // data
  //           << std::endl;
  psram->writeByte(addr, data);
}

uint16_t
sdram_read(uint32_t addr) {
  std::cerr << std::hex;
  std::cerr << "DPI-C sdram read @ " << addr
            << " data = " << psram->readHalf(addr) << std::endl;
  assert(sdram && "De-ref nullptr");
  return sdram->readHalf(addr);
}

void
sdram_write(uint32_t addr, unsigned short data, unsigned char mask) {
  std::cerr << std::hex;
  std::cerr << "DPI-C sdram write @ " << addr << " data = " << data
            << " mask = " << (uint16_t)mask << std::endl;
  assert(sdram && "De-ref nullptr");
  sdram->writeHalf(addr, data, mask);
}
