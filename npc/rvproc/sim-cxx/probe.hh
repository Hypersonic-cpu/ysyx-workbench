#pragma once
#include "options.hh"
#include "verilated.h"

#include <array>
#include <cstdint>
#include <exception>
#include <format>
#include <iomanip>
#include <iostream>
#include <list>
#include <stdexcept>
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
using handler_t = void (*)();
extern handler_t dumpHandler;

template <typename... Args>
inline void
v_assert(bool cond, const Args&... args) {
  if (!cond) [[unlikely]] {
    std::cerr << ANSI_RED "[ASSERT FAILED] " << __FILE__ << ":" << __LINE__
              << " " ANSI_NONE << std::hex;
    ((std::cerr << args << " "), ...);
    std::cerr << std::endl;
    // vl_fatal(__FILE__, __LINE__, "v_assert", "FAIL");
    // throw std::runtime_error("Assertion failed");
    dumpHandler();
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
} // namespace trace
