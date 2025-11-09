#pragma once
#include <cstdint>
#include <algorithm>

namespace dpic {
  std::pair<bool, uint32_t> pmem_probe(uint32_t addr);
}
