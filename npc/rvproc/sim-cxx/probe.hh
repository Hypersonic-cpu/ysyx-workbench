#pragma once
#include "verilated.h"
#include <array>
#include <cstdint>
#include <iomanip>
#include <iostream>
#include <string>
#include <unordered_map>

#define ANSI_NONE "\033[0m"
#define ANSI_RED "\033[31m"
#define ANSI_GREEN "\033[32m"
#define ANSI_YELLOW "\033[33m"
#define ANSI_B_RED "\033[1;31m"
#define ANSI_B_GREEN "\033[1;32m"

using addr_t = uint32_t;
using ureg_t = uint32_t;

template <typename... Args>
inline void
v_assert(bool cond, const Args&... args) {
  if (!cond) {
    std::cerr << ANSI_RED "[ASSERT FAILED] " << __FILE__ << ":" << __LINE__
              << " " ANSI_NONE << std::hex;
    ((std::cerr << args << " "), ...);
    std::cerr << std::endl;
    vl_fatal(__FILE__, __LINE__, "v_assert", "FAIL");
  }
}

template <typename... Args>
inline void
v_warn(bool cond, const Args&... args) {
  if (!cond) {
    std::cerr << ANSI_YELLOW "[WARN COND] " << __FILE__ << ":" << __LINE__
              << " " ANSI_NONE << std::hex;
    ((std::cerr << args << " "), ...);
    std::cerr << std::endl;
  }
}

namespace util {

inline uint32_t
bmask(unsigned hi, unsigned lo) {
  return (~0U >> (31 - hi)) << lo;
}

inline uint32_t
bits(uint32_t num, unsigned hi, unsigned lo) {
  return (num >> lo) & bmask(hi - lo, 0);
}

inline uint32_t
sext(uint32_t num, unsigned bitnum) {
  const unsigned shift = 32 - bitnum;
  return static_cast<uint32_t>(static_cast<int32_t>(num << shift) >> shift);
}

// template<unsigned N>
// class SgnExtHelper {
//   signed int val : N;
// };
//
// template<unsigned N>
// inline uint32_t
// sext(uint32_t num) {
//   SgnExtHelper<N> tmp;
//   tmp.val = num;
//   return static_cast<uint32_t> (tmp.val);
// }

inline std::ostream&
sout32(std::ostream& os, char fill = '0', std::string prefix = "0x") {
  std::ios::fmtflags original_flags = os.flags();
  os << prefix << std::setfill(fill) << std::setw(8) << std::hex;
  return os;
}
} // namespace util

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

/** Global var */
extern RingBuffer<InstEnt, 16> instBuf;
extern RingBuffer<MemEnt, 16> memBuf;

void mem_acc_log(uint32_t addr, bool is_write, uint32_t data,
                 uint8_t byte_mask);

struct ElfSymEnt {
  std::string name;
  uint32_t addr;
  uint32_t size;
};

extern std::unordered_map<uint32_t, ElfSymEnt> elf_syms;

extern bool log_ena;
extern std::string log_wavefile;
extern std::string elf_file;

// enum DelayTime{
//   CurrCyc = 0,
//   PrevCyc = 1,
//   Num_DelayTime
// };
// extern std::array<bool, Num_DelayTime> device_access;
extern bool device_access;

struct WriteEvent {
  addr_t aligned;
  ureg_t data;
  uint8_t mask;
};
// TODO: Merge with MemRingBuffer
extern WriteEvent mem_write_buf;

constexpr unsigned RegNum{16U};
constexpr unsigned FunctArgs{6U};
constexpr std::array<std::string, RegNum + 1> RegName{
  "$0", "ra", "sp", "gp", "tp", "t0", "t1", "t2", "s0",
  "s1", "a0", "a1", "a2", "a3", "a4", "a5", "pc"};

extern bool fast;
} // namespace trace

// NOTE: 这是main用于窥探dpic SV 的namespace.
// DPI-C 选择暴露这些接口. 定义应该在 pememacc.cc.
namespace dpic {
std::pair<bool, uint32_t> pmem_probe(uint32_t addr);

uint8_t* pmem_pointer_raw();
size_t pmem_bytes_raw();
} // namespace dpic
