#pragma once 

#include <array>
#include <cstdint>
#include <algorithm>
#include <iomanip>
#include <iostream>
#include <iterator>
#include <list>
#include <ostream>
#include <regex>
#include <stack>
#include <string>
#include <utility>
#include <vector>

#include "VrvCore.h"
#include "VrvCore___024root.h"

#include "disasm.hh"
#include "probe.hh"

namespace ccdb {
  using ptop_t = const TOP_NAME*;
  extern ptop_t top;

  std::pair<bool, uint32_t> read_reg(uint8_t regid);

  // 0x10 for PC
  inline std::pair<bool, uint32_t> 
  _read_verilator_reg(uint8_t regid) {
    auto r = top->rootp;
    uint32_t ret = 0;
    bool valid = true;
    switch (regid) {
      case 0x0: ret = r->rvCore__DOT__iReg__DOT__gprs_0; break;
      case 0x1: ret = r->rvCore__DOT__iReg__DOT__gprs_1; break;
      case 0x2: ret = r->rvCore__DOT__iReg__DOT__gprs_2; break;
      case 0x3: ret = r->rvCore__DOT__iReg__DOT__gprs_3; break;
      case 0x4: ret = r->rvCore__DOT__iReg__DOT__gprs_4; break;
      case 0x5: ret = r->rvCore__DOT__iReg__DOT__gprs_5; break;
      case 0x6: ret = r->rvCore__DOT__iReg__DOT__gprs_6; break;
      case 0x7: ret = r->rvCore__DOT__iReg__DOT__gprs_7; break;
      case 0x8: ret = r->rvCore__DOT__iReg__DOT__gprs_8; break;
      case 0x9: ret = r->rvCore__DOT__iReg__DOT__gprs_9; break;
      case 0xa: ret = r->rvCore__DOT__iReg__DOT__gprs_10; break;
      case 0xb: ret = r->rvCore__DOT__iReg__DOT__gprs_11; break;
      case 0xc: ret = r->rvCore__DOT__iReg__DOT__gprs_12; break;
      case 0xd: ret = r->rvCore__DOT__iReg__DOT__gprs_13; break;
      case 0xe: ret = r->rvCore__DOT__iReg__DOT__gprs_14; break;
      case 0xf: ret = r->rvCore__DOT__iReg__DOT__gprs_15; break;
      case 0x10: ret = r->rvCore__DOT__pc; break;
      default: valid = false; break;
    }
    return std::make_pair(valid, ret);
  }

  std::pair<bool, uint32_t> read_mem(uint32_t addr);

  void inline trace_init() { 
    init_disasm(); 
    init_elfsym();
  }

  // void inst_trace(uint32_t pc);
  void inst_trace();

  class FrameEnt {
    public:
    // Stack pointer before callee modify it.
    unsigned depth;
    std::string name;
    uint32_t addr;
    // uint32_t sp;
    uint32_t ra;
    std::array<uint32_t, comm::FunctArgs> args;
    void printent(std::ostream& os, const std::string& prefix="", bool indent=false) const {
      if (indent) { std::string space(depth, ' '); os << space; }
      os << prefix << " ";
      os << "[" << std::setfill(' ') << std::setw(3) << std::dec << depth << "] "; 
      comm::sout32(os) << addr << " : " << name << "(";
      for (auto arg: args) {
        // comm::sout32(os, ' ') << arg << ", ";
        os << std::hex << "0x" << arg << ", ";
      }
      os << ")" << std::endl;
    }
  };
  extern std::list<FrameEnt> frame_stk;

  void frame_trace(uint32_t snpc, uint32_t dst, bool is_ret);

  struct DumpPrint {
    bool mem_buf = true;
    bool frame_stk = true;
    bool inst_buf = true;
    bool reg_file = true;
    bool elf_symbol = true;
  };
  extern DumpPrint runtime_dump_opt;

  inline void 
  inst_dump(std::ostream& os=std::cerr) {
    os << "\n=== Inst Ring Buffer === " << std::endl;
    for (size_t i = 0; i < comm::instBuf.size(); i++) {
      comm::instBuf.atidx(i).printent(os);
    }
  }

  inline void 
  frame_dump(std::ostream& os=std::cerr) {
    os << "\n=== Frame Stack === " << std::endl;
    for (const auto& ent : ccdb::frame_stk) {
      ent.printent(os);
    }
  }

  inline void 
  regfile_dump(std::ostream& os=std::cerr) {
    os << "\n=== Register File === " << std::endl;
    for (size_t i = 0; i < comm::RegNum+1; ++i) {
      os << std::setfill(' ') << "[";
      if (i == comm::RegNum) {
        os << "  ";
      } else {
        os << std::dec << std::setw(2) << i;
      }
      os << "] " << comm::RegName.at(i) << " : ";
      comm::sout32(os) << read_reg(i).second << std::endl;
    }
  }

  inline void 
  memacc_dump(std::ostream& os=std::cerr) {
    os << "\n=== Mem Ring Buffer === " << std::endl;
    for (size_t i = 0; i < comm::memBuf.size(); i++) {
      comm::memBuf.atidx(i).printent(os);
    }
  }

  inline void 
  elftable_dump(std::ostream& os=std::cerr) {
    os <<  "\n === ELF Funct Symbols (" << comm::elf_syms.size() << " total) === ";
    os << std::endl;
    for (auto const& [addr, ent] : comm::elf_syms) {
      comm::sout32(os) << addr;
      os << " size " << std::dec << std::setfill(' ') << std::setw(6) << ent.size;
      os << " : " << ent.name << std::endl;
    }
  }

  void dump_print(const DumpPrint& opt);
}
