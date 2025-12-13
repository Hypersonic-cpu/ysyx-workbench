#pragma once

#include "ccdb.hh"
#include "probe.hh"
#include <cstddef>
#include <cstdint>
#include <unistd.h>
#include <utility>
#include <vector>

namespace diff {
using mcpy_t = void (*)(uint32_t addr, void *buf, size_t n, bool direction);
using rcpy_t = void (*)(void *dut, bool direction);
using exec_t = void (*)(uint64_t n);
using intr_t = void (*)(uint64_t no);
using init_t = void (*)(int port);
using memw_t = void (*)(void *dst);

extern init_t ref_init;
extern exec_t ref_exec;
extern mcpy_t ref_memcpy;
extern rcpy_t ref_regcpy;
extern intr_t ref_raise_intr;
extern memw_t ref_cpy_memwr_event;

extern bool enable;

struct CpyDir {
  constexpr static bool ToDut = 0;
  constexpr static bool ToRef = 1;
};

constexpr char NEMU_SO[] = "build/riscv32-nemu-interpreter-so";

constexpr int NEMUPort{1234};

constexpr uint32_t ResetVector{0x8000'0000};

std::vector<std::tuple<uint8_t, uint32_t, uint32_t>> match();
void copy();
void iota(uint64_t n = 1);
void init(const char *so = NEMU_SO, int port = NEMUPort);
//
// class StateMatcher {
//   using McState = ccdb::McState;
//
// private:
//   McState state;
//
// public:
//   StateMatcher() : state{McState::Strt} {}
//   void
//   iota() {
//     switch (state) {
//     case McState::Fire:
//       state = McState::Hold;
//       break;
//     case McState::Hold:
//       state = McState::Idle;
//       break;
//     case McState::Idle:
//       state = McState::Fire;
//       break;
//     case McState::Strt:
//       state = McState::Fire;
//       break;
//     default:
//       comm::v_assert(false, "No such state", (int)state);
//     }
//   }
//
//   std::pair<bool, McState>
//   match_golden(uint32_t state_in) const {
//     return match_golden(McState(state_in));
//   }
//
//   std::pair<bool, McState>
//   match_golden(McState in) const {
//     return std::make_pair(in == state, state);
//   }
//
//   void
//   force_state(uint32_t state_in) {
//     force_state(McState(state_in));
//   }
//   void
//   force_state(McState in) {
//     state = in;
//   }
// };
//
std::pair<bool, const comm::WriteEvent>
match_memwr(const comm::WriteEvent& real);

// extern StateMatcher state_checker;
} // namespace diff
