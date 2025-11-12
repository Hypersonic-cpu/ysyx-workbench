#include "difftest.hh"

#include "ccdb.hh"
#include "probe.hh"

#include <cassert>
#include <cstdlib>
#include <string>
#include <dlfcn.h>

namespace diff {
  init_t ref_init = nullptr;
  exec_t ref_exec = nullptr;
  mcpy_t ref_memcpy = nullptr;
  rcpy_t ref_regcpy = nullptr;
  intr_t ref_raise_intr = nullptr;
}

// NOTE: Must after the NPC memory is initialized.
void 
diff::init(const char* so, int port) {
  auto nemu_path = getenv("NEMU_HOME");
  std::string so_file = (so[0] == '/') ? (std::string(so)) : 
    (std::string(nemu_path) + std::string("/") + std::string(so));
  // TODO: Change to RTLD_LAZY
  void* dl = dlopen(so_file.c_str(), RTLD_NOW); 
  assert(dl && "DiffTest ref .so open failed");
  
  diff::ref_init   = (init_t) dlsym(dl, "difftest_init");
  diff::ref_exec   = (exec_t) dlsym(dl, "difftest_exec");
  diff::ref_memcpy = (mcpy_t) dlsym(dl, "difftest_memcpy");
  diff::ref_regcpy = (rcpy_t) dlsym(dl, "difftest_regcpy");
  diff::ref_raise_intr = (intr_t) dlsym(dl, "difftest_raise_intr");

  assert(ref_init && "difftest_init");
  assert(ref_exec && "difftest_exec");
  assert(ref_memcpy && "difftest_memcpy");
  assert(ref_regcpy && "difftest_regcpy");
  assert(ref_raise_intr && "difftest_raise_intr");

  ref_init(port);

  auto imgsz = dpic::pmem_bytes_raw();
  assert(imgsz > 0 && imgsz < (1U << 30) /* 1GiB */ && "Strange image size");
  ref_memcpy(diff::ResetVector, dpic::pmem_pointer_raw(), imgsz, CpyDir::ToRef);

  // TODO: Enable RVE for NEMU
  uint32_t regbuf[comm::RegNum+1];
  for (size_t i = 0; i < comm::RegNum+1; ++i) {
    regbuf[i] = ccdb::read_reg(i).second;
  }
  ref_regcpy(regbuf, CpyDir::ToRef);
}
