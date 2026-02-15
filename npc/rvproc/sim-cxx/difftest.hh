#pragma once
#include "options.hh"
#include "probe.hh"

#include <cstddef>
#include <cstdint>
#include <dlfcn.h>
#include <format>
#include <string>
#include <unistd.h>
#include <utility>
#include <vector>

namespace trace {

class DiffTester {
  using mcpy_t = void (*)(uint32_t addr, void* buf, size_t n,
                          bool direction);
  using rcpy_t = void (*)(void* dut, bool direction);
  using exec_t = void (*)(uint64_t n);
  using intr_t = void (*)(uint64_t no);
  using init_t = void (*)(int port);
  using memw_t = void (*)(void* dst);

private:
  init_t ref_init;
  exec_t ref_exec;
  mcpy_t ref_memcpy;
  rcpy_t ref_regcpy;
  intr_t ref_raise_intr;
  // memw_t ref_cpy_memwr_event;

  bool device_access;
  bool fire;
  ureg_t delayed_ref_pc;
  ureg_t delayed_dut_pc;

private:
  void
  init(const std::vector<ureg_t>& image, const char* so = NEMU_SO,
       int port = NEMUPort) {
    if constexpr (!options::diff_enable)
      return;
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
    // ref_cpy_memwr_event = (memw_t)dlsym(dl, "difftest_get_memwr_event");

    assert(ref_init && "difftest_init");
    assert(ref_exec && "difftest_exec");
    assert(ref_memcpy && "difftest_memcpy");
    assert(ref_regcpy && "difftest_regcpy");
    assert(ref_raise_intr && "difftest_raise_intr");
    // assert(ref_cpy_memwr_event && "difftest_get_memwr_event");

    ref_init(port);

    auto imgsz = image.size() * 4;
    ref_memcpy(ResetVector, (void*)const_cast<ureg_t*>(image.data()), imgsz,
               CpyDir::ToRef);
  }

public:
  DiffTester(const std::vector<ureg_t>& image)
      : device_access{false}
      , fire{false}
      , delayed_dut_pc{0xffff'ffffU}
      , delayed_ref_pc{ResetVector} {
    init(image);
  }

  struct CpyDir {
    constexpr static bool ToDut = 0;
    constexpr static bool ToRef = 1;
  };

  static constexpr char NEMU_SO[] = "build/riscv32-nemu-interpreter-so";
  static constexpr int NEMUPort{1234};

  // TODO: Add Device Check back
  // bool is_csr =
  //   bits(inst, 6, 2) == 0b11100 && bits(inst, 14, 12) != 0b000;
  // uint16_t csrid = bits(inst, 31, 20);
  // bool diff_csrs =
  //   (csrid == 0xB00 || csrid == 0xB80 || csrid == 0xF11 || csrid ==
  //   0xF12);
  // if (is_csr && diff_csrs) {
  //   device_access = true;
  // }

  auto
  match() noexcept -> std::vector<std::tuple<uint16_t, uint32_t, uint32_t>> {
    if constexpr (!options::diff_enable) {
      return {};
    }
    if (device_access) [[unlikely]]
      return {};
    std::vector<std::tuple<uint16_t, uint32_t, uint32_t>> ret{};
    uint32_t regbuf[RegNum + 1];
    ref_regcpy(regbuf, CpyDir::ToDut);
    std::swap(regbuf[RegNum], delayed_ref_pc);

    // printf("Matching : REF PC %08x DUT PC %08x\n", regbuf[RegNum],
    //        delayed_dut_pc);

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
  copy() noexcept {
    if constexpr (!options::diff_enable)
      return;
    device_access = false;
    uint32_t regbuf[RegNum + 1];
    for (size_t i = 0; i < RegNum + 1; ++i) {
      regbuf[i] = trace::read_reg(i);
    }
    ref_regcpy(regbuf, CpyDir::ToRef);
  }

  void
  iota(uint64_t n = 1) noexcept {
    if constexpr (!options::diff_enable)
      return;
    ref_exec(n);
  }

  auto
  test_on_commit() noexcept
    -> std::vector<std::tuple<uint16_t, uint32_t, uint32_t>> {
    if (!fire) {
      return {};
    }
    fire = false;

    iota();
    auto ret = match();
    copy();
    return std::move(ret);
  }

  void
  upd_dut_pc(ureg_t pc) {
    delayed_dut_pc = pc;
  }

  void
  setFire() {
    fire = true;
  }
};
} // namespace trace
