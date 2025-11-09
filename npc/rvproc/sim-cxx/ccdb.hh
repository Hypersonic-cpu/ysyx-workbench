#pragma once 

#include <cstdint>
#include <algorithm>
#include <utility>
#include "VrvCore.h"
#include "VrvCore___024root.h"
#include ""
#include "pmemacc.hh"

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
  read_mem(ptop_t top, uint32_t addr) {
    return dpic::pmem_probe(addr);
  }
}
