#include "ccdb.hh"
#include "probe.hh"
#include "VrvCore.h"
#include "VrvCore___024root.h"

#include <cassert>
#include <cstdint>
#include <list>
#include <unordered_map>
#include <utility>
#include <ranges>

using ccdb::top;

std::pair<bool, uint32_t>
ccdb::read_reg(uint8_t regid) {
  return ccdb::_read_verilator_reg(top, regid);
}

std::pair<bool, uint32_t>
ccdb::read_mem(uint32_t addr) {
  return dpic::pmem_probe(addr);
}

void 
ccdb::inst_trace() {
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

    auto [v, src1] = read_reg(rs1);
    auto dst = is_jalr ? 
      ((immI + src1) & (~1U)) : (immJ + pc);
    // Check ELF symbol for pc / dst
    ccdb::frame_trace(pc, dst, rd == 0);
  }
}

std::list<ccdb::FrameEnt> ccdb::frame_stk {};

void 
ccdb::frame_trace(uint32_t snpc, uint32_t dst, bool is_ret) {
  using comm::elf_syms;
  using ccdb::frame_stk;
  auto it = elf_syms.find(dst);
  auto [_, sp] = read_reg(2);
  if (is_ret && it != elf_syms.end()) {
    // NOTE: Jump to a symbol, with rd == 0, 
    // should be a TCO function call.

    // TCO psuedo ret of current frame. 
    unsigned depth = 0;
    if (frame_stk.empty()) {
      std::cerr << "TCO on empty frame stack, change to simply alloc" << std::endl;
    } else {
      auto temp = frame_stk.back();
      depth = temp.depth;
      frame_stk.back().printent(std::cerr, "- [TCO]");
      frame_stk.pop_back();
    }
    // Alloc new frame 
    frame_stk.emplace_back(
        depth, it->second->name, it->second->addr, sp);
    frame_stk.back().printent(std::cerr, "+");
  } else if (it != elf_syms.end()) {
    // Normal function call.
    auto depth = frame_stk.empty() ? 0U : (frame_stk.back().depth+1);
    frame_stk.emplace_back(
        depth, it->second->name, it->second->addr, sp);
    frame_stk.back().printent(std::cerr, "+");
  } else if (is_ret) {
    // function return
    auto ir = frame_stk.rbegin();
    for (; ir != frame_stk.rend(); ir++) {
      // The sp equals the sp at function call (before frame alloc).
      // => Matches!
      if (ir->sp == sp) { break; }
    }
    if (ir == frame_stk.rend()) { return; }
    else {
      frame_stk.back().printent(std::cerr, "-");
      // Should not skip !
      assert(&(*ir) == &frame_stk.back());
      frame_stk.pop_back();
    }
  }

  // NOTE: rd == 0 并不一定是 ret, 也有可能是 TCO.
  // 另外, void funct() { while (1) { ... } } 也会造成类似的情况. 
  // 需要根据stack操作辨别. 也可以直接无视, 因为无穷尾递归和 while (1) 
  // 没什么区别. (但不应压栈)
  //
}

