#include "probe.hh"

tick_t
curr_tick() noexcept {
  return read_double_csr(trace::MCycleh, trace::MCycle);
}
