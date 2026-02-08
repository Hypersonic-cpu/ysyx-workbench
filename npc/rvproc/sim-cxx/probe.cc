#include "probe.hh"

tick_t
curr_tick() noexcept {
  return g_global_tick;
  // return read_double_csr(trace::MCycleh, trace::MCycle);
}
