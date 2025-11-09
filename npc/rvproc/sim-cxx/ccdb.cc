#include "ccdb.hh"

#include "VrvCore.h"
#include "VrvCore___024root.h"
#include <cassert>
#include <iomanip>
#include <iostream>

void 
ccdb::inst_trace(uint32_t pc) {
  auto [v, inst] = read_mem(pc);
  assert(v && "ccdb inst read fail");

  std::cerr << "0x" << std::setw(8) << std::hex << pc << " : ";
  std::cerr << "0x" << std::setw(8) << std::hex << inst << std::endl;
}
