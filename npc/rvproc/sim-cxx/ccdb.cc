#include "ccdb.hh"

#include <cstdint>
#include <utility>

std::pair<bool, uint32_t>
ccdb::read_reg(ptop_t top, uint8_t regid) {
  return ccdb::_read_verilator_reg(top, regid);
}

std::pair<bool, uint32_t>
ccdb::read_mem(uint32_t addr) {
  return dpic::pmem_probe(addr);
}

void 
ccdb::inst_trace(uint32_t pc) {
  auto [v, inst] = ccdb::read_mem(pc);
  assert(v && "ccdb inst read fail");

  constexpr size_t BufferLen{ 256U };
  char buf[BufferLen] = {0};
  void disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte);
  disassemble(buf, BufferLen, pc, (uint8_t*) (&inst), 4);

  auto ent = comm::InstEnt{ pc, inst, buf };
  comm::instBuf.append(ent);
  ent.printent(std::cerr);
}

