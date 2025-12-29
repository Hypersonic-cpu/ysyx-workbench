#pragma once

#include "options.hh"

#include <cassert>
#include <memory>
#include <string>
#include <verilated_fst_c.h>

namespace trace {

class FstTracer {
private:
  const std::unique_ptr<VerilatedFstC> tfp;
  const std::string path;

public:
  FstTracer() = delete;
  FstTracer(const FstTracer& other) = delete;
  FstTracer& operator=(const FstTracer& other) = delete;

  FstTracer(const std::string& path = "")
      : tfp(new VerilatedFstC), path{path} {}
  ~FstTracer() { tfp->close(); }

  void
  open() {
    if constexpr (options::wave_enable) {
      tfp->open(path.c_str());
      assert(tfp->isOpen());
    }
  }

  void
  dump(uint64_t t) const {
    if constexpr (options::wave_enable) {
      tfp->dump(t);
    }
  }

  auto
  get() const noexcept {
    return tfp.get();
  }

  void
  close() { tfp->close(); }
};

} // namespace trace
