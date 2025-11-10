#include "probe.hh"

namespace comm {
  RingBuffer<InstEnt, 16> instBuf {}; 
  RingBuffer<MemEnt, 16> memBuf {};
  std::vector<ElfSymEnt> elf_syms {};
}

void 
comm::mem_acc_log(uint32_t addr, bool is_write, uint32_t data, uint8_t byte_mask) {
  memBuf.append(MemEnt{ addr, is_write, data, byte_mask }).printent(std::cerr);
}
