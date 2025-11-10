#pragma once
#include <iostream>

namespace ccdb {
  void pmem_init_hello() { std::cerr << "HELLO" << std::endl; exit(0); }
}
