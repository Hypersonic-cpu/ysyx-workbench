#pragma once
#include <cstdint>
#include <iostream>

namespace ccdb {
  void pmem_init_hello();

  void pmem_access(
      uint32_t addr, bool is_write, uint32_t data, uint8_t byte_mask);
}
