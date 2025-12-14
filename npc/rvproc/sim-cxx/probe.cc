#include "probe.hh"
#include <unordered_map>

namespace trace {
RingBuffer<InstEnt, 16> instBuf{};
RingBuffer<MemEnt, 16> memBuf{};
std::unordered_map<uint32_t, ElfSymEnt> elf_syms{};

// std::array<bool, Num_DelayTime> device_access{false, false};
bool device_access{false};
WriteEvent mem_write_buf{0, 0};

bool log_ena{false};
std::string log_wavefile{};
std::string elf_file{};

bool fast{false};
} // namespace comm

void
trace::mem_acc_log(uint32_t addr, bool is_write, uint32_t data,
                  uint8_t byte_mask) {
  memBuf.append(MemEnt{addr, is_write, data, byte_mask}).printent(std::cerr);
}
