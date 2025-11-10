#pragma once 

#include <array>
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
  std::ostream& sout32(std::ostream& os, std::string prefix="0x") {
    os << prefix << std::setfill('0') << std::setw(8) << std::hex;
    return os;
  }
}

namespace ccdb {
  template<class T, std::size_t N>
  class RingBuffer {
    public:
      RingBuffer() : ptr{ 0U }, buf{} {}
      void append(const T& t) {
        buf.at(ptr).~T();
        new (&buf.at(ptr)) T(t);
        ptr = (ptr + 1) % N;
      }

      T const atmod(size_t idx) const {
        return buf.at(idx % N);
      }

      T& atmod(size_t idx) {
        return buf.at(idx % N);
      }

      void printbuf(std::ostream& os, const std::string& title) {
        os << "\n === " << title << " === " << std::endl;
        for (size_t i = 0; i < N; i++) {
          atmod(i).printent(os);
        }
      }

    protected:
      size_t ptr;
      std::array<T, N> buf;
  };
  
  class InstEnt {
    public:
      uint32_t const pc;
      uint32_t const inst;
      std::string const disasm;
      void printent(std::ostream& os) {
        cfmt::sout32(os) << pc << " : ";
        cfmt::sout32(os, "") << inst << " \t" << disasm;
        os << std::endl;
      }
  };

  class MemEnt {
    public:
      uint32_t const addr;
      uint32_t const value;
      bool const is_write;
      void printent(std::ostream& os) {
        os << (is_write ? "Write" : "Read ");
        cfmt::sout32(os, " @ 0x") << addr << " : ";
        cfmt::sout32(os, "") << value;
        os << std::endl;
      }
  };

  /** Global var */
  RingBuffer<InstEnt, 16> instBuf {};
  RingBuffer<MemEnt, 16> memBuf {};

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

    constexpr size_t BufferLen{ 256U };
    char buf[BufferLen] = {0};
    void disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte);
    disassemble(buf, BufferLen-1, pc, (uint8_t*) (&inst), 4);

    auto ent = InstEnt{ pc, inst, buf };
    instBuf.append(ent);
    ent.printent(std::cerr);
  }
}
