#include "ccdb.hh"

void 
ccdb::inst_trace(uint32_t pc) {
  auto [v, inst] = read_mem(pc);
  assert(v && "ccdb inst read fail");

  constexpr size_t BufferLen{ 256U };
  char buf[BufferLen] = {0};
  void disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte);
  disassemble(buf, BufferLen, pc, (uint8_t*) (&inst), 4);

  auto ent = InstEnt{ pc, inst, buf };
  instBuf.append(ent);
  ent.printent(std::cerr);
}

