#include <cassert>
#include <cstdint>

#include <fstream>
#include <iomanip>
#include <iostream>
#include <verilated.h>
// #define PRINTF_COND 1

const char PMemFile[] = "/mnt/hgfs/Arch-PA/ysyx-workbench/npc/rvproc/prog-rom/meminit.bin";
constexpr size_t PMemSize{ 0x1000'0000U }; // 32 MiB
static uint32_t pmem_raw[PMemSize >> 2];

constexpr uint32_t BaseAddr{ 0x8000'0000U };
constexpr auto ValidAccess = [](size_t idx) -> bool {
  return idx < (PMemSize >> 2);
};

extern "C" void 
pmem_init() {
#if PRINTF_COND
  std::cout << "DPI-C >> pmem_init called" << std::endl;
#endif
  std::ifstream ifs(PMemFile, std::ios::binary | std::ios::in);
  assert(ifs.is_open());

  ifs.seekg(0, std::ios::end);
  auto const file_size = ifs.tellg();
#if PRINTF_COND
  std::cout << "DPI-C >> file size " << std::dec << file_size << std::endl;
#endif
  assert(file_size != std::ifstream::pos_type(-1));
  ifs.seekg(0, std::ios::beg);

  ifs.read((char *) pmem_raw, file_size);
#if PRINTF_COND
  std::cout << "DPI-C >> fail " << ifs.fail() << " eof " << ifs.eof() << std::endl;
#endif
  assert(!ifs.fail());

  // for (size_t i = 0x0; i < 0x20; i++) {
  //   if (i % 4 == 0) {
  //     std::cout << std::hex << i << ":\t";
  //   }
  //   std::cout << std::hex << std::setfill('0') << std::setw(8) << pmem_raw[i] << " ";
  //   if (i % 4 == 3) {
  //     std::cout << std::endl;
  //   }
  // }
}

// pmem_init() {
// #if PRINTF_COND
//   std::cout << "DPI-C >> pmem_init called" << std::endl;
// #endif
//   std::ifstream ifs (PMemFile);
//   assert(ifs.is_open());
//
//   std::string rline{};
//   size_t pos{ 0U };
//   while (std::getline(ifs, rline)) {
//     assert (pos < (PMemSize >> 2) && "Mem init out of bound");
//     std::stringstream ss {rline};
//     ss >> std::hex >> pmem_raw[pos++];
//   }
//   ifs.close();
// }

extern "C" uint32_t 
pmem_read(uint32_t raddr) {
#if PRINTF_COND
  std::cout << "DPI-C >> pmem_read addr " << std::hex << raddr << std::endl;
#endif
  if (raddr == 0) { return 0; }
  uint32_t aln_idx = (raddr - BaseAddr) >> 2;
  assert(ValidAccess(aln_idx) && "PMem out of bound");
#if PRINTF_COND
  std::cout << " ret = " << std::hex << pmem_raw[aln_idx] << std::endl;
#endif
  return pmem_raw[aln_idx];
}

extern "C" void
pmem_write(uint32_t waddr, uint32_t wdata, uint8_t wmask) {
  uint32_t aln_idx = (waddr - BaseAddr) >> 2;
  assert(ValidAccess(aln_idx) && "PMem out of bound");
  uint32_t m = 0U;
  for (int i = 0; i < 4; i++) {
    if (wmask & (1 << i)) {
      m |= (0xff << (i * 8));
    }
  }
  pmem_raw[aln_idx] = 
    (pmem_raw[aln_idx] & ~m) | (wdata & m);

#if PRINTF_COND
  std::cout << "DPI-C >> pmem_write addr" << std::hex << waddr << " : " << wdata << " mask = " << m << std::endl;
  for (size_t i = 0x100 >> 2; i < (0x100+20) >> 2; i++) {
    if (i % 4 == 0) {
      std::cout << std::hex << i << ":\t";
    }
    std::cout << std::hex << pmem_raw[i] << " ";
    if (i % 4 == 3) {
      std::cout << std::endl;
    }
  }
#endif
}
