#pragma once
#include "nlohmann/json.hpp"
#include <iostream>

using json = nlohmann::ordered_json;

using addr_t = uint32_t;
using tick_t = size_t;
using tint_t = uint32_t;

extern tick_t curr_tick() noexcept;

using addr_t = uint32_t;
using ureg_t = uint32_t;
// using blen_t = uint16_t;
//
#if SOCMODE
constexpr addr_t ResetVector{0x3000'0000U};
#else
constexpr addr_t ResetVector{0x8000'0000U};
#endif
constexpr addr_t SerialAddr{0x1000'0000U};

using handler_t = void (*)();
extern handler_t abortHandler;
extern handler_t resetAllStats;
extern handler_t dumpAllStats;

#define ANSI_NONE "\033[0m"
#define ANSI_RED "\033[31m"
#define ANSI_GREEN "\033[32m"
#define ANSI_YELLOW "\033[33m"
#define ANSI_B_RED "\033[1;31m"
#define ANSI_B_GREEN "\033[1;32m"

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
    abortHandler();
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

inline std::ostream&
sout32(std::ostream& os, char fill = '0', std::string prefix = "0x") {
  std::ios::fmtflags original_flags = os.flags();
  os << prefix << std::setfill(fill) << std::setw(8) << std::hex;
  return os;
}
} // namespace util
