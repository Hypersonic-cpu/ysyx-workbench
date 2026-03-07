#pragma once
#include "probe.hh"

#include <cstddef>
#include <cstdint>
#include <dlfcn.h>
#include <format>
#include <queue>
#include <string>
#include <unistd.h>
#include <utility>
#include <vector>

namespace trace {

class DiffTester {
public:
  static constexpr char NEMU_SO[] = "build/riscv32-nemu-interpreter-so";
  static constexpr int NEMUPort{1234};

  struct CpyDir {
    constexpr static bool ToDut = 0;
    constexpr static bool ToRef = 1;
  };

private:
  using mcpy_t = void (*)(uint32_t addr, void* buf, size_t n,
                          bool direction);
  using rcpy_t = void (*)(void* dut, bool direction);
  using exec_t = void (*)(uint64_t n);
  using intr_t = void (*)(uint64_t no);
  using init_t = void (*)(int port);

  init_t ref_init;
  exec_t ref_exec;
  mcpy_t ref_memcpy;
  rcpy_t ref_regcpy;
  intr_t ref_raise_intr;

  bool fire;
  bool skipMatch;
  ureg_t delayed_ref_pc;
  ureg_t delayed_dut_pc;
  uint64_t commitCount;

  // FIFO of cycle numbers when device accesses were detected.
  // A commit on a LATER cycle than the front entry is the device access.
  std::queue<tick_t> devAccessCycles;

private:
  static inline uint32_t
  bits(uint32_t v, int hi, int lo) {
    return (v >> lo) & ((1U << (hi - lo + 1)) - 1);
  }

  void init(const std::vector<ureg_t>& image, const char* so = NEMU_SO,
            int port = NEMUPort);

public:
  DiffTester(const std::vector<ureg_t>& image)
#if DIFFENA
      : fire{false}
      , skipMatch{false}
      , delayed_dut_pc{0xffff'ffffU}
      , delayed_ref_pc{ResetVector}
      , commitCount{0}
      , devAccessCycles{}
#endif
  {
#if DIFFENA
    init(image);
#endif
  }

  void checkSkipMatch(uint32_t inst);

  auto match() noexcept
    -> std::vector<std::tuple<uint16_t, uint32_t, uint32_t>>;

  void copy() const noexcept;

  void iota(uint64_t n = 1) const noexcept;

  auto test_on_commit() noexcept
    -> std::vector<std::tuple<uint16_t, uint32_t, uint32_t>>;

  void updateDutPC(ureg_t pc);
  void setFire();
  void setDeviceAccess();
};
} // namespace trace
