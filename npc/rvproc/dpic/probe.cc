#include "probe.hh"
#include <unordered_map>

namespace comm {
  RingBuffer<InstEnt, 16> instBuf {}; 
  RingBuffer<MemEnt, 16> memBuf {};
  std::unordered_map<uint32_t, ElfSymEnt> elf_syms {};
}

void 
comm::mem_acc_log(uint32_t addr, bool is_write, uint32_t data, uint8_t byte_mask) {
  memBuf.append(MemEnt{ addr, is_write, data, byte_mask })
    // .printent(std::cerr)
    ;
}
