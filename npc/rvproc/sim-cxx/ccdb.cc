#include "ccdb.hh"
#include "probe.hh"
#include "VrvCore.h"
#include "VrvCore___024root.h"

#include <cstdint>
#include <stack>
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
ccdb::inst_trace(ccdb::ptop_t top) {
  auto pc = top->rootp->rvCore__DOT__pc;
  auto [v, inst] = ccdb::read_mem(pc);
  assert(v && "ccdb inst read fail");

  constexpr size_t BufferLen{ 256U };
  char buf[BufferLen] = {0};
  void disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte);
  disassemble(buf, BufferLen, pc, (uint8_t*) (&inst), 4);

  auto ent = comm::InstEnt{ pc, inst, buf };
  comm::instBuf.append(ent);
  // ent.printent(std::cerr);

  bool is_jalr = comm::bits(inst, 6, 2) == 0b11001;
  bool is_jal  = comm::bits(inst, 6, 2) == 0b11011;
  if (is_jalr || is_jal) {
    uint8_t rd  = comm::bits(inst, 11,  7);
    uint8_t rs1 = comm::bits(inst, 19, 15);
    uint32_t immI = comm::sext(
        comm::bits(inst, 31, 20), 12);
    uint32_t immJ = comm::sext(
      (comm::bits(inst, 31, 31) << 20) | 
      (comm::bits(inst, 19, 12) << 12) |
      (comm::bits(inst, 20, 20) << 11) |
      (comm::bits(inst, 30, 21) <<  1), 21);

    auto [v, src1] = read_reg(top, rs1);
    auto dst = is_jalr ? 
      ((immI + src1) & (~1U)) : (immJ + pc);
    // Check ELF symbol for pc / dst
    ccdb::frame_trace(pc, dst, rd == 0);
  }
}

std::stack<ccdb::FrameEnt> ccdb::frameStk {};

void 
ccdb::frame_trace(uint32_t snpc, uint32_t dst, bool is_ret) {

}

