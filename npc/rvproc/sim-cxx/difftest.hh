#pragma once

#include <cstddef>
#include <cstdint>

namespace diff {
  using mcpy_t = void (*)(uint32_t addr, void *buf, size_t n, bool direction);
  using rcpy_t = void (*)(void *dut, bool direction);
  using exec_t = void (*)(uint64_t n);
  using intr_t = void (*)(uint64_t no);
  using init_t = void (*)(int port);

  extern init_t ref_init;
  extern exec_t ref_exec;
  extern mcpy_t ref_memcpy;
  extern rcpy_t ref_regcpy;
  extern intr_t ref_raise_intr;

  struct CpyDir {
    constexpr static bool ToDut = 0;
    constexpr static bool ToRef = 1;
  };

  constexpr char NEMU_SO[] = "build/riscv32-nemu-interpreter-so";

  constexpr int NEMUPort{ 1234 };

  constexpr uint32_t ResetVector{ 0x8000'0000 };

  bool match();
  void iota();
  void init(const char* so=NEMU_SO, int port=NEMUPort);
}
