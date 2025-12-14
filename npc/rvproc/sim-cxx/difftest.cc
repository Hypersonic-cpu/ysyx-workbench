// #include "difftest.hh"
//
// #include "ccdb.hh"
// #include "probe.hh"
//
// #include <cassert>
// #include <cstddef>
// #include <cstdlib>
// #include <dlfcn.h>
// #include <format>
// #include <iterator>
// #include <string>
// #include <tuple>
// #include <utility>

// namespace diff {
// init_t ref_init = nullptr;
// exec_t ref_exec = nullptr;
// mcpy_t ref_memcpy = nullptr;
// rcpy_t ref_regcpy = nullptr;
// intr_t ref_raise_intr = nullptr;
// memw_t ref_cpy_memwr_event = nullptr;
// bool enable = true;
// // StateMatcher state_checker{};
// } // namespace diff

// void
// diff::copy() {
//   comm::device_access = comm::device_access;
  // comm::device_access = false;
  // comm::device_access[comm::PrevCyc] = comm::device_access[comm::CurrCyc];
  // comm::device_access[comm::CurrCyc] = false;
  // comm::mem_write_buf = {0, 0, 0};
  // uint32_t regbuf[comm::RegNum + 1];
  // for (size_t i = 0; i < comm::RegNum + 1; ++i) {
  //   regbuf[i] = ccdb::read_reg(i).second;
  // }
  // ref_regcpy(regbuf, CpyDir::ToRef);
// }
//
// std::pair<bool, const comm::WriteEvent>
// diff::match_memwr(const comm::WriteEvent &real) {
//   comm::WriteEvent ref{};
//   diff::ref_cpy_memwr_event(&ref);
//   ureg_t bitmask = 0;
//   for (auto i = 0U; i < 3; i++) {
//     if (ref.mask & (1 << i))
//       bitmask |= (0xff << (i << 3));
//   }
//   auto eq = real.aligned == ref.aligned && real.mask == ref.mask &&
//             (real.data & bitmask) == (ref.data & bitmask);
//   constexpr addr_t SerialAddr = 0x1000'0000;
//   if (ref.aligned == SerialAddr) {
//     eq = true;
//   }
//   return std::make_pair(eq, ref);
// }
