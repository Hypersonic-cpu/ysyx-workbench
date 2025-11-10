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

  void init_disasm();

  void disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte);
} // namespace ccdb 
