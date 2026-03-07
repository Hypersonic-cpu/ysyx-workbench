#include "difftest.hh"
#include "rtl_defs.hh"
#include <cstdint>

namespace trace {

#if DIFFENA
void
DiffTester::init(const std::vector<ureg_t>& image, const char* so,
                 int port) {
  auto nemu_path = getenv("NEMU_HOME");
  std::string so_file =
    (so[0] == '/')
      ? (std::string(so))
      : (std::string(nemu_path) + std::string("/") + std::string(so));
  void* dl = dlopen(so_file.c_str(), RTLD_NOW | RTLD_GLOBAL);
  v_assert(dl, "DiffTest .so", so_file, "open failed:", dlerror());
  std::cerr << std::format("Using difftest .so {}", so_file) << std::endl;

  ref_init = (init_t)dlsym(dl, "difftest_init");
  ref_exec = (exec_t)dlsym(dl, "difftest_exec");
  ref_memcpy = (mcpy_t)dlsym(dl, "difftest_memcpy");
  ref_regcpy = (rcpy_t)dlsym(dl, "difftest_regcpy");
  ref_raise_intr = (intr_t)dlsym(dl, "difftest_raise_intr");

  assert(ref_init && "difftest_init");
  assert(ref_exec && "difftest_exec");
  assert(ref_memcpy && "difftest_memcpy");
  assert(ref_regcpy && "difftest_regcpy");
  assert(ref_raise_intr && "difftest_raise_intr");

  ref_init(port);

  auto imgsz = image.size() * 4;
  ref_memcpy(ResetVector, (void*)const_cast<ureg_t*>(image.data()), imgsz,
             CpyDir::ToRef);
}

void
DiffTester::checkSkipMatch(uint32_t inst) {
  bool is_csr = bits(inst, 6, 2) == 0b11100 && bits(inst, 14, 12) != 0b000;
  if (!is_csr)
    return;
  uint16_t csrid = bits(inst, 31, 20);
  if (csrid == 0xB00 || csrid == 0xB80)
    skipMatch = true;
}

auto
DiffTester::match() noexcept
  -> std::vector<std::tuple<uint16_t, uint32_t, uint32_t>> {
  std::vector<std::tuple<uint16_t, uint32_t, uint32_t>> ret{};
  uint32_t regbuf[RegNum + 1];
  ref_regcpy(regbuf, CpyDir::ToDut);
  std::swap(regbuf[RegNum], delayed_ref_pc);

  size_t i = 0;
  for (i = 0; i < RegNum + 1; ++i) {
    auto dut = (i == RegNum) ? delayed_dut_pc : trace::read_reg(i);
    if (regbuf[i] != dut) {
      ret.emplace_back(i, regbuf[i], dut);
    }
  }
  return std::move(ret);
}

void
DiffTester::copy() const noexcept {
  uint32_t regbuf[RegNum + 1];
  for (size_t i = 0; i < RegNum; ++i) {
    regbuf[i] = trace::read_reg(i);
  }
  // Preserve NEMU's PC (delayed_ref_pc), don't overwrite with DUT's
  // committed PC which is the current instruction, not the next.
  regbuf[RegNum] = delayed_ref_pc;
  ref_regcpy(regbuf, CpyDir::ToRef);
}

void
DiffTester::iota(uint64_t n) const noexcept {
  ref_exec(n);
}

auto
DiffTester::test_on_commit() noexcept
  -> std::vector<std::tuple<uint16_t, uint32_t, uint32_t>> {
  if (!fire) {
    return {};
  }
  fire = false;
  commitCount++;

  // Device access skip: notify_ls_req fires in MEM stage on the
  // SAME cycle as a different instruction's commit in WB. The
  // device access instruction commits on a LATER cycle. Use the
  // cycle timestamp to distinguish.
  bool skipDevice = false;
  if (!devAccessCycles.empty() && devAccessCycles.front() < curr_tick()) {
    skipDevice = true;
    devAccessCycles.pop();
  }

  if (skipDevice || skipMatch) {
    // Execute NEMU (it won't crash — NEMU mmio handles unmapped
    // addresses gracefully). Then skip comparison and sync DUT
    // state to NEMU.
    iota();
    uint32_t regbuf[RegNum + 1];
    ref_regcpy(regbuf, CpyDir::ToDut);
    delayed_ref_pc = regbuf[RegNum];
    skipMatch = false;
    copy();
    return {};
  }

  iota();
  std::vector<std::tuple<uint16_t, uint32_t, uint32_t>> ret{};
  ret = match();
  if (!ret.empty()) {
    std::cerr << std::format(
      "Commit #{}: dut_pc={:08x} ref_pc_delayed={:08x}", commitCount,
      delayed_dut_pc, delayed_ref_pc)
              << std::endl;
  }
  copy();
  return std::move(ret);
}

void
DiffTester::setDeviceAccess() {
  devAccessCycles.push(curr_tick());
}

void
DiffTester::updateDutPC(ureg_t pc) {
  delayed_dut_pc = pc;
}

void
DiffTester::setFire() {
  fire = true;
}

#else  // !DIFFENA

void
DiffTester::init(const std::vector<ureg_t>& image, const char* so,
                 int port) {}
void
DiffTester::checkSkipMatch(uint32_t inst) {}
auto
DiffTester::match() noexcept
  -> std::vector<std::tuple<uint16_t, uint32_t, uint32_t>> {
  return {};
}

void
DiffTester::copy() const noexcept {}

void
DiffTester::iota(uint64_t n) const noexcept {}

auto
DiffTester::test_on_commit() noexcept
  -> std::vector<std::tuple<uint16_t, uint32_t, uint32_t>> {
  return {};
}

void
DiffTester::updateDutPC(ureg_t pc) {}
void
DiffTester::setFire() {}
void
DiffTester::setDeviceAccess() {}
#endif // DIFFENA

} // namespace trace
