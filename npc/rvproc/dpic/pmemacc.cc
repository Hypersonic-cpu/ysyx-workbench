#include <cassert>
#include <cstdint>

#include <fstream>
#include <iostream>
#include <ostream>
#include <sstream>
#include <string>
#include <verilated.h>

const char PMemFile[] = "/mnt/hgfs/Arch-PA/ysyx-workbench/npc/rvproc/prog-rom/meminit.hex";
constexpr size_t PMemSize{ 1U << 25 }; // 32 MiB
static uint32_t pmem_raw[PMemSize >> 2];

extern "C" void 
pmem_init() {
  std::cout << "DPI-C >> pmem_init called" << std::endl;
  std::ifstream ifs (PMemFile);
  assert(ifs.is_open());

  std::string rline{};
  size_t pos{ 0U };
  while (std::getline(ifs, rline)) {
    assert (pos < (PMemSize >> 2) && "Mem init out of bound");
    std::stringstream ss {rline};
    ss >> std::hex >> pmem_raw[pos++];
  }
  ifs.close();
}

extern "C" uint32_t 
pmem_read(uint32_t raddr) {
  std::cout << "DPI-C >> pmem_read addr " << std::hex << raddr;
  uint32_t aligned_index = raddr >> 2;
  assert(aligned_index < (PMemSize >> 2) && "PMem out of bound");
  std::cout << " ret = " << std::hex << pmem_raw[aligned_index] << std::endl;
  return pmem_raw[aligned_index];
}

extern "C" void
pmem_write(uint32_t waddr, uint32_t wdata, uint8_t wmask) {
  uint32_t aligned_index = waddr >> 2;
  assert(aligned_index < (PMemSize >> 2) && "PMem out of bound");
  uint32_t m = 0U;
  for (int i = 0; i < 4; i++) {
    if (wmask & (1 << i)) {
      m |= (0xff << (i * 8));
    }
  }
  pmem_raw[aligned_index] = (m & wdata);

  std::cout << "DPI-C >> pmem_write addr" << std::hex << waddr << " : " << wdata << " mask = " << (uint32_t) wmask << std::endl;
  for (size_t i = 0x100; i < 0x100+20; i++) {
    if (i % 4 == 0) {
      std::cout << std::hex << i << ":\t";
    }
    std::cout << std::hex << pmem_raw[i] << " ";
    if (i % 4 == 3) {
      std::cout << std::endl;
    }
  }
}
