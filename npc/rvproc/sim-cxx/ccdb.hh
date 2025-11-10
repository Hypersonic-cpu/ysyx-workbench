#pragma once 

#include <array>
#include <cstdint>
#include <algorithm>
#include <iomanip>
#include <iostream>
#include <iterator>
#include <ostream>
#include <string>
#include <utility>
#include <vector>

#include "VrvCore.h"
#include "VrvCore___024root.h"

#include "disasm.hh"
#include "probe.hh"

namespace ccdb {

  typedef const std::unique_ptr<TOP_NAME>& ptop_t;
  std::pair<bool, uint32_t> read_reg(ptop_t top, uint8_t regid);

  // 0xff for PC
  inline std::pair<bool, uint32_t> 
  _read_verilator_reg(ptop_t top, uint8_t regid) {
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
      default: valid = false; break;
    }
    return std::make_pair(valid, ret);
  }

  std::pair<bool, uint32_t> read_mem(uint32_t addr);

  void inline trace_init() { 
    init_disasm(); 
    init_elfsym(NULL);
  }

  // void inst_trace(uint32_t pc);
  void inst_trace(uint32_t pc);
}
