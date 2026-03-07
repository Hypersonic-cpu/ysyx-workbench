#pragma once

#include <cassert>
#include <memory>
#include <string>

#ifdef LOGENA
#include <verilated_fst_c.h>
#endif // LOGENA

namespace trace {

class FstTracer {
#if LOGENA
private:
  const std::unique_ptr<VerilatedFstC> tfp;
  const std::string path;

public:
  FstTracer() = delete;
  FstTracer(const FstTracer& other) = delete;
  FstTracer& operator=(const FstTracer& other) = delete;

  FstTracer(const std::string& path = "")
      : tfp(new VerilatedFstC)
      , path{path} {}
  ~FstTracer() { tfp->close(); }

  void
  open() {
    tfp->open(path.c_str());
    assert(tfp->isOpen());
  }

  void
  dump(uint64_t t) const {
    tfp->dump(t);
  }

  auto
  get() const noexcept {
    return tfp.get();
  }

  void
  close() {
    tfp->close();
  }
#else
public:
  FstTracer() = delete;
  FstTracer(const FstTracer& other) = delete;
  FstTracer& operator=(const FstTracer& other) = delete;

  FstTracer(const std::string& path = "") {}
  ~FstTracer() { }

  void
  open() {}

  void
  dump(uint64_t t) const {}

  auto
  get() const noexcept {
    return nullptr;
  }

  void
  close() {}
#endif // LOGENA
};

} // namespace trace
