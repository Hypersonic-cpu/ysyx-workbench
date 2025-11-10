#include "dpic.hh"
// #include "ccdb.hh"

#include <iostream>

void 
ccdb::pmem_init_hello() { 
  std::cerr << "HELLO" << std::endl; exit(0);
}

void 
ccdb::pmem_access(uint32_t addr, bool is_write, uint32_t data, uint8_t byte_mask) {
  ccdb::memBuf.append(ccdb::MemEnt{ addr, is_write, data, byte_mask }).printent(std::cerr);
}
