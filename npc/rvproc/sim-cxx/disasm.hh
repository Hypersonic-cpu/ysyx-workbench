#pragma once

#include <cstdlib>
#include <cassert>
#include <dlfcn.h>
#include <capstone/capstone.h>
#include <string>

namespace ccdb {
typedef size_t (*cs_disasm_dl_t)(csh handle, const uint8_t *code,
    size_t code_size, uint64_t address, size_t count, cs_insn **insn);
typedef void   (*cs_free_dl_t)(cs_insn *insn, size_t count);
typedef cs_err (*cs_open_dl_t)(cs_arch arch, cs_mode mode, csh *handle);

static cs_disasm_dl_t cs_disasm_dl = nullptr;
static cs_free_dl_t   cs_free_dl   = nullptr;
static cs_open_dl_t   cs_open_dl   = nullptr;

static csh handle;

void init_disasm() {
  auto nemu_path = std::string(std::getenv("NEMU_HOME"));

  void *dl_handle;
  dl_handle = dlopen((nemu_path + std::string("/tools/capstone/repo/libcapstone.so.5")).c_str(), RTLD_LAZY);
  assert(dl_handle);

  cs_open_dl = (cs_open_dl_t) dlsym(dl_handle, "cs_open");
  assert(cs_open_dl);

  cs_disasm_dl = (cs_disasm_dl_t) dlsym(dl_handle, "cs_disasm");
  assert(cs_disasm_dl);

  cs_free_dl = (cs_free_dl_t) dlsym(dl_handle, "cs_free");
  assert(cs_free_dl);

  cs_arch arch = CS_ARCH_RISCV;
  cs_mode mode = cs_mode(CS_MODE_RISCV32 | CS_MODE_RISCVC);
	[[maybe_unused]] int ret = cs_open_dl(arch, mode, &handle);
  assert(ret == CS_ERR_OK);
}

void disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte) {
	cs_insn *insn;
	size_t count = cs_disasm_dl(handle, code, nbyte, pc, 0, &insn);
  assert(count == 1);
  int ret = snprintf(str, size, "%s", insn->mnemonic);
  if (insn->op_str[0] != '\0') {
    snprintf(str + ret, size - ret, "\t%s", insn->op_str);
  }
  cs_free_dl(insn, count);
}

} // namespace ccdb 
