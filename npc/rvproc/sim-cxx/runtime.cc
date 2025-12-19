#include "runtime.hh"
#include <cassert>
#include <cstdint>
#include <memory>

const RuntimeBin* mrom = nullptr;
const RuntimeBin* flash = nullptr;

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
