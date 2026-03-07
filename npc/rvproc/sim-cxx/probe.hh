#pragma once
#include "rtl_defs.hh"

// clang-format off
#include <array>
#include <cstdint>
#include <iomanip>
#include <iostream>
#include <list>
#include <string>
#include <sys/cdefs.h>
#include <unordered_map>

#if SOCMODE
#include "VysyxSoCFull.h"
#include "VysyxSoCFull___024root.h"
#else
#include "VrvCore.h"
#include "VrvCore___024root.h"
#endif

namespace trace {
class InstEnt {
public:
  uint32_t const pc;
  uint32_t const inst;
  std::string const disasm;
  void
  printent(std::ostream& os) const {
    util::sout32(os) << pc << " : ";
    util::sout32(os, '0', "") << inst << " \t" << disasm;
    os << std::endl;
  }
};

class MemEnt {
public:
  uint32_t const addr;
  bool const is_write;
  uint32_t const value;
  // Otherwise ostream<< will treat it as char.
  uint16_t addr_mask;
  void
  printent(std::ostream& os) const {
    os << (is_write ? "Write" : "Read ");
    util::sout32(os, '0', " @ 0x") << addr << " : ";
    util::sout32(os, ' ', "") << value;
    if (is_write) {
      os << " mask " << std::hex << std::setw(1) << addr_mask;
    }
    os << std::endl;
  }
};

template <class T, std::size_t N> class RingBuffer {
public:
  RingBuffer() : ptr{0U}, buf{} {}
  const T&
  append(const T& t) {
    buf.at(ptr).~T();
    new (&buf.at(ptr)) T(t);
    auto const& ret = buf.at(ptr);
    ptr = (ptr + 1) % N;
    return ret;
  }

  T const
  atmod(size_t idx) const {
    return buf.at(idx % N);
  }

  T&
  atmod(size_t idx) {
    return buf.at(idx % N);
  }

  const T
  atidx(size_t idx) const {
    return buf.at((idx + ptr + N) % N);
  }

  T&
  atidx(size_t idx) {
    return buf.at((idx + ptr + N) % N);
  }

  void
  printbuf(std::ostream& os, const std::string& title) const {
    os << "\n === " << title << " === " << std::endl;
    for (size_t i = 0; i < N; i++) {
      atmod(i).printent(os);
    }
  }

  size_t
  size() const {
    return N;
  }

  size_t
  head() const {
    return ptr;
  }

protected:
  size_t ptr;
  std::array<T, N> buf;
};

struct WriteEvent {
  addr_t aligned;
  ureg_t data;
  uint8_t mask;
};
// TODO: Merge with MemRingBuffer
extern WriteEvent mem_write_buf;

constexpr unsigned RegNum{32U};
constexpr unsigned FunctArgs{6U};
constexpr std::array<std::string, RegNum + 1> RegName{
  "$0",  "ra",  "sp",  "gp",  "tp",  "t0",  "t1",  "t2",
  "s0",  "s1",  "a0",  "a1",  "a2",  "a3",  "a4",  "a5",
  "a6",  "a7",  "s2",  "s3",  "s4",  "s5",  "s6",  "s7",
  "s8",  "s9",  "s10", "s11", "t3",  "t4",  "t5",  "t6",
  "pc"};

struct ElfSymEnt {
  std::string name;
  uint32_t addr;
  uint32_t size;
};

class FrameEnt {
public:
  // Stack pointer before callee modify it.
  unsigned depth;
  std::string name;
  uint32_t addr;
  // uint32_t sp;
  uint32_t ra;
  std::array<uint32_t, FunctArgs> args;
  void
  printent(std::ostream& os, const std::string& prefix = "",
           bool indent = false) const {
    if (indent) {
      std::string space(depth, ' ');
      os << space;
    }
    os << prefix << " ";
    os << "[" << std::setfill(' ') << std::setw(3) << std::dec << depth
       << "] ";
    util::sout32(os) << addr << " : " << name << "(";
    for (auto arg : args) {
      // sout32(os, ' ') << arg << ", ";
      os << std::hex << "0x" << arg << ", ";
    }
    os << ")" << std::endl;
  }
};

using ibuf_t = RingBuffer<InstEnt, 16>;
using mbuf_t = RingBuffer<MemEnt, 16>;
using ebuf_t = std::unordered_map<uint32_t, ElfSymEnt>;
using fbuf_t = std::list<FrameEnt>;

using ptop_t = const TOP_NAME*;
extern ptop_t ptop;

// 0x10 for PC
inline ureg_t
read_reg(uint8_t regid) noexcept {
  auto r = ptop->rootp;
  ureg_t ret = 0;
  switch (regid) {
#if SOCMODE
    case 0x0: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_0; break;
    case 0x1: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_1; break;
    case 0x2: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_2; break;
    case 0x3: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_3; break;
    case 0x4: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_4; break;
    case 0x5: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_5; break;
    case 0x6: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_6; break;
    case 0x7: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_7; break;
    case 0x8: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_8; break;
    case 0x9: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_9; break;
    case 0xa: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_10; break;
    case 0xb: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_11; break;
    case 0xc: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_12; break;
    case 0xd: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_13; break;
    case 0xe: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_14; break;
    case 0xf: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_15; break;
    case 0x10: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_16; break;
    case 0x11: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_17; break;
    case 0x12: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_18; break;
    case 0x13: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_19; break;
    case 0x14: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_20; break;
    case 0x15: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_21; break;
    case 0x16: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_22; break;
    case 0x17: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_23; break;
    case 0x18: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_24; break;
    case 0x19: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_25; break;
    case 0x1a: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_26; break;
    case 0x1b: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_27; break;
    case 0x1c: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_28; break;
    case 0x1d: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_29; break;
    case 0x1e: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_30; break;
    case 0x1f: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_31; break;
    case 0x20:ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT___wbs_io_toReg_bits_excpPC; break;
#else
    case 0x0: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_0; break;
    case 0x1: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_1; break;
    case 0x2: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_2; break;
    case 0x3: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_3; break;
    case 0x4: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_4; break;
    case 0x5: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_5; break;
    case 0x6: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_6; break;
    case 0x7: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_7; break;
    case 0x8: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_8; break;
    case 0x9: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_9; break;
    case 0xa: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_10; break;
    case 0xb: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_11; break;
    case 0xc: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_12; break;
    case 0xd: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_13; break;
    case 0xe: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_14; break;
    case 0xf: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_15; break;
    case 0x10: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_16; break;
    case 0x11: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_17; break;
    case 0x12: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_18; break;
    case 0x13: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_19; break;
    case 0x14: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_20; break;
    case 0x15: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_21; break;
    case 0x16: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_22; break;
    case 0x17: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_23; break;
    case 0x18: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_24; break;
    case 0x19: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_25; break;
    case 0x1a: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_26; break;
    case 0x1b: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_27; break;
    case 0x1c: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_28; break;
    case 0x1d: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_29; break;
    case 0x1e: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_30; break;
    case 0x1f: ret = r->rvCore__DOT__reg_0__DOT__gpr__DOT__gprs_31; break;
    case 0x20:ret = r->rvCore__DOT___wbs_io_toReg_bits_excpPC ; break;
#endif
    default: v_assert(false, "Invalid GPR read @ regid =", std::to_string(regid));
      break;
  }
  return ret;
}

constexpr std::array<const char*, 4> csr_list {
  "mtvec", "mepc", "mstatus", "mcause"
};

enum CsrSel {
  MTvec = 0, MEpc, MStatus, MCause,
  MCycle, MCycleh, MInstret, MInstreth,
  Num_CsrSel
};

inline ureg_t
read_csr(CsrSel fakeid) noexcept {
  auto r = ptop->rootp;
  ureg_t ret = 0;
  switch (fakeid) {
#if SOCMODE
    case MTvec:    ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__csr__DOT__mtvec    ; break;
    case MEpc:     ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__csr__DOT__mepc     ; break;
    case MStatus:  ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__csr__DOT__mstatus  ; break;
    case MCause:   ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__csr__DOT__mcause   ; break;
    case MCycle:   ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__csr__DOT__mcycle   ; break;
    case MCycleh:  ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__csr__DOT__mcycleh  ; break;
    case MInstret: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__csr__DOT__minstret ; break;
    case MInstreth:ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__csr__DOT__minstreth; break;
#else
    case MTvec:    ret = r->rvCore__DOT__reg_0__DOT__csr__DOT__mtvec    ; break;
    case MEpc:     ret = r->rvCore__DOT__reg_0__DOT__csr__DOT__mepc     ; break;
    case MStatus:  ret = r->rvCore__DOT__reg_0__DOT__csr__DOT__mstatus  ; break;
    case MCause:   ret = r->rvCore__DOT__reg_0__DOT__csr__DOT__mcause   ; break;
    case MCycle:   ret = r->rvCore__DOT__reg_0__DOT__csr__DOT__mcycle   ; break;
    case MCycleh:  ret = r->rvCore__DOT__reg_0__DOT__csr__DOT__mcycleh  ; break;
    case MInstret: ret = r->rvCore__DOT__reg_0__DOT__csr__DOT__minstret ; break;
    case MInstreth:ret = r->rvCore__DOT__reg_0__DOT__csr__DOT__minstreth; break;
#endif
    default: v_assert(false, "Out-of-range CSR read, sim fakeid =", std::to_string(fakeid)); break;
  }
  return ret;
}

inline size_t
read_double_csr(CsrSel hi, CsrSel lo) noexcept {
  size_t ret = read_csr(hi);
  ret <<= 32;
  ret |= read_csr(lo);
  return ret;
}

// enum CacheSel {
//   CacheAccLo = 0, CacheAccHi,
//   CacheHitLo, CacheHitHi,
// };
//
// inline ureg_t
// read_cache_pmu(CacheSel sel) {
//   auto r = ptop->rootp;
//   ureg_t ret = 0;
//   switch (sel) {
// #if SOCMODE
//     default: assert(false && "Unimpl");
// #else
//     case CacheAccHi: ret = r->rvCore__DOT__l1iPort__DOT__pmu__DOT__accCountHi; break;
//     case CacheAccLo: ret = r->rvCore__DOT__l1iPort__DOT__pmu__DOT__accCountLo; break;
//     case CacheHitHi: ret = r->rvCore__DOT__l1iPort__DOT__pmu__DOT__hitCountHi; break;
//     case CacheHitLo: ret = r->rvCore__DOT__l1iPort__DOT__pmu__DOT__hitCountLo; break;
// #endif
//     default: v_assert(false, "Out-of-range Cache PMU read, id =", std::to_string(sel)); break;
//   }
//   return ret;
// }
//
// inline uint64_t
// read_cache_perf(CacheSel hi, CacheSel lo) noexcept {
//   uint64_t ret = read_cache_pmu(hi);
//   ret <<= 32;
//   ret |= read_cache_pmu(lo);
//   return ret;
// }

// inline bool
// read_arbiter_rport() noexcept {
//   auto r = ptop->rootp;
  // return r->rvcore__DOT__ ???

// WARN: This is not at WB stage.
// inline bool
// read_raw_stall() noexcept {
//   auto r = ptop->rootp;
//   return r->rvCore__DOT__ids__DOT__io_rawRes;
// }

// inline bool
// read_lsu_stall() noexcept {
//   auto r = ptop->rootp;
//   return r->rvCore__DOT__lss__DOT__state;
// }

// No inst commit is regarded as a stall.
// inline bool
// read_stall() noexcept {
//   auto r = ptop->rootp;
//   return !r->rvCore__DOT__wbs_io_in_valid_r;
// }

} // namespace trace
