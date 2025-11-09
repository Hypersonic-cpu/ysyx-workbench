#pragma once 

#include <cstdint>
#include <algorithm>
#include <iomanip>
#include <iostream>
#include <iterator>
#include <ostream>
#include <utility>
#include "VrvCore.h"
#include "VrvCore___024root.h"

#include "pmemacc.hh"
#include "disasm.hh"

namespace cfmt {
  std::ostream& sout32(std::ostream& os) {
    os << "0x" << std::setfill('0') << std::setw(8) << std::hex;
    return os;
  }
}

namespace ccdb {
  // enum class RegIdx : uint8_t {
  //   zero = 0,
  //
  // }

  typedef const std::unique_ptr<TOP_NAME>& ptop_t;
  // 0xff for PC
  std::pair<bool, uint32_t> 
  read_reg(ptop_t top, uint8_t regid) {
    auto r = top->rootp;
    uint32_t ret = 0;
    bool valid = true;
    switch (regid) {
      case 0x0: ret = r->rvCore__DOT__iReg__DOT__regs_0; break;
      case 0x1: ret = r->rvCore__DOT__iReg__DOT__regs_1; break;
      case 0x2: ret = r->rvCore__DOT__iReg__DOT__regs_2; break;
      case 0x3: ret = r->rvCore__DOT__iReg__DOT__regs_3; break;
      case 0x4: ret = r->rvCore__DOT__iReg__DOT__regs_4; break;
      case 0x5: ret = r->rvCore__DOT__iReg__DOT__regs_5; break;
      case 0x6: ret = r->rvCore__DOT__iReg__DOT__regs_6; break;
      case 0x7: ret = r->rvCore__DOT__iReg__DOT__regs_7; break;
      case 0x8: ret = r->rvCore__DOT__iReg__DOT__regs_8; break;
      case 0x9: ret = r->rvCore__DOT__iReg__DOT__regs_9; break;
      case 0xa: ret = r->rvCore__DOT__iReg__DOT__regs_10; break;
      case 0xb: ret = r->rvCore__DOT__iReg__DOT__regs_11; break;
      case 0xc: ret = r->rvCore__DOT__iReg__DOT__regs_12; break;
      case 0xd: ret = r->rvCore__DOT__iReg__DOT__regs_13; break;
      case 0xe: ret = r->rvCore__DOT__iReg__DOT__regs_14; break;
      case 0xf: ret = r->rvCore__DOT__iReg__DOT__regs_15; break;
      case 0xff: ret = r->rvCore__DOT__pc; break;
      default: valid = false;
    }
    return std::make_pair(valid, ret);
  }

  std::pair<bool, uint32_t>
  read_mem(uint32_t addr) {
    return dpic::pmem_probe(addr);
  }

  void 
  trace_init() {
    init_disasm();
  }

  void 
  inst_trace(uint32_t pc) {
    auto [v, inst] = read_mem(pc);
    assert(v && "ccdb inst read fail");

    cfmt::sout32(std::cerr) << pc << " : ";
    cfmt::sout32(std::cerr) << inst << std::endl;
  }
}
