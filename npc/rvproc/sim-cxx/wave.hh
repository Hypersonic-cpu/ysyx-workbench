#pragma once

#include <cassert>
#include <memory>
#include <string>
#include <verilated_fst_c.h>
namespace trace {

template <bool E> class FstTracer {
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
    if constexpr (E) {
      tfp->open(path.c_str());
      assert(tfp->isOpen());
    }
  }

  void
  dump(uint64_t t) const {
    if constexpr (E) {
      tfp->dump(t);
    }
  }

  auto
  get() const noexcept {
    return tfp.get();
  }
};

} // namespace trace
