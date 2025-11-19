#include "ccdb.hh"
#include "probe.hh"
#include "VrvCore.h"
#include "VrvCore___024root.h"

#include <cassert>
#include <cstdint>
#include <iterator>
#include <list>
#include <unordered_map>
#include <utility>
#include <ranges>

using ccdb::top;

ccdb::DumpPrint ccdb::runtime_dump_opt{ 0, 0, 0, 0, 0 };

std::pair<bool, uint32_t>
ccdb::read_reg(uint8_t regid) {
  return ccdb::_read_verilator_reg(regid);
}

std::pair<bool, uint32_t>
ccdb::read_csr(uint8_t fakeid) {
  return ccdb::_read_verilator_csr(fakeid);
}

std::pair<bool, uint32_t>
ccdb::read_mem(uint32_t addr) {
  return dpic::pmem_probe(addr);
}

void 
ccdb::inst_trace() {
  auto pc = read_reg(comm::RegNum).second;
  auto [v, inst] = ccdb::read_mem(pc);
  assert(v && "ccdb inst read fail");

  constexpr size_t BufferLen{ 256U };
  char buf[BufferLen] = {0};
  void disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte);
  disassemble(buf, BufferLen, pc, (uint8_t*) (&inst), 4);

  auto ent = comm::InstEnt{ pc, inst, buf };
  comm::instBuf.append(ent);
  if (ccdb::runtime_dump_opt.inst_buf) {
    ent.printent(std::cerr);
  }

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
    ccdb::frame_trace(pc+4, dst, rd == 0);
  }

  bool is_csr = 
    comm::bits(inst, 6, 2) == 0b11100 &&
    comm::bits(inst, 14, 12) != 0b000;
  uint16_t csrid = comm::bits(inst, 31, 20);
  bool diff_csrs = (csrid == 0xF11 || csrid == 0xF12);
  if (is_csr && diff_csrs) { 
    comm::device_access[comm::PrevCyc] = true; 
  }
}

std::list<ccdb::FrameEnt> ccdb::frame_stk {};

void 
ccdb::frame_trace(uint32_t snpc, uint32_t dst, bool is_ret) {
  using comm::elf_syms;
  using ccdb::frame_stk;
  auto it = elf_syms.find(dst);
  auto const read_args = [](){
    std::array<uint32_t, comm::FunctArgs> aret {};
    for (size_t i = 0; i < comm::FunctArgs; i++) {
      aret.at(i) = read_reg(10U+i).second;
    }
    return aret;
  };

  using ccdb::runtime_dump_opt;
  // auto [_v2, sp] = read_reg(2);
  if (is_ret && it != elf_syms.end()) { // NOTE: TCO
    // Jump to a symbol, with rd == 0, 
    // TCO psuedo ret of current frame. 
    unsigned depth = 0;
    unsigned ra = 0;
    if (frame_stk.empty()) {
      std::cerr << "TCO on empty frame stack, change to simply alloc" << std::endl;
    } else {
      auto temp = frame_stk.front();
      depth = temp.depth;
      ra = temp.ra;
      if (runtime_dump_opt.frame_stk)
        frame_stk.front().printent(std::cerr, "- [TCO]", true);
      frame_stk.pop_front();
    }
    // Alloc new frame, but ra remains.
    frame_stk.emplace_front(
        depth, it->second.name, it->second.addr, ra, 
        read_args());
    if (runtime_dump_opt.frame_stk)
      frame_stk.front().printent(std::cerr, "+", true);
  } else if (it != elf_syms.end()) { // NOTE: Normal function call.
    auto depth = frame_stk.empty() ? 0U : (frame_stk.front().depth+1);
    // The static NPC (PC of jal +4) is ra
    frame_stk.emplace_front(
        depth, it->second.name, it->second.addr, snpc,
        read_args());
    if (runtime_dump_opt.frame_stk)
      frame_stk.front().printent(std::cerr, "+", true);
  } else if (is_ret) { // NOTE: function return
    auto ir = frame_stk.begin();
    for (; ir != frame_stk.end(); ir++) {
      if (ir->ra == dst) {
        // Jump back => true ret.
        break;
      }
      // The sp equals the sp at function call (before frame alloc).
      // => Matches ? 
      // WARN:使用 sp 是不准确的. 用 ra 会更好.
    }
    if (ir == frame_stk.end()) { return; }
    else {
      if (runtime_dump_opt.frame_stk)
        frame_stk.front().printent(std::cerr, "-", true);
      // Should not skip !
      assert(&(*ir) == &frame_stk.front());
      frame_stk.pop_front();
    }
  }
}


void 
ccdb::dump_print(const ccdb::DumpPrint& opt) {
  if (opt.reg_file) { regfile_dump(); }
  if (opt.inst_buf) { inst_dump(); }
  if (opt.mem_buf) { memacc_dump(); }
  if (opt.frame_stk) { frame_dump(); }
}
