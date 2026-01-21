#include "probe.hh"

size_t
curr_tick() noexcept {
  return read_double_csr(trace::MCycleh, trace::MCycle);
}
