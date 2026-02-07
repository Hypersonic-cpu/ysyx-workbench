#pragma once

#include <cstdint>
#include <iostream>

#if SOCMODE
using tick_t = uint64_t;
__attribute_noinline__
size_t curr_tick() noexcept;
#else

// Use npSim global clock and types.
#include "defines/types.hh"
extern tick_t curr_tick() noexcept;

#endif

#define ANSI_NONE "\033[0m"
#define ANSI_RED "\033[31m"
#define ANSI_GREEN "\033[32m"
#define ANSI_YELLOW "\033[33m"
#define ANSI_B_RED "\033[1;31m"
#define ANSI_B_GREEN "\033[1;32m"

using addr_t = uint32_t;
using ureg_t = uint32_t;
using handler_t = void (*)();
extern handler_t abortHandler;
extern handler_t resetAllStats;
extern handler_t dumpAllStats;

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

template <typename... Args>
inline void
v_warn_dec(bool cond, const Args&... args) {
  if (!cond) {
    std::cerr << ANSI_YELLOW "[WARN COND] " << __FILE__ << ":" << __LINE__
              << " " ANSI_NONE << std::dec;
    ((std::cerr << args << " "), ...);
    std::cerr << std::endl;
  }
}
