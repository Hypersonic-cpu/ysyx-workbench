#pragma once

#include <array>
#include <capstone/capstone.h>
#include <cassert>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <format>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>

#include "options.hh"
#include "probe.hh"

namespace trace {

void elftable_dump(const ebuf_t& elfSyms, std::ostream& os = std::cerr);
void inst_dump(const ibuf_t& instBuf, std::ostream& os = std::cerr);
void frame_dump(const fbuf_t& frameStk, std::ostream& os = std::cerr);
void membuf_dump(const mbuf_t& memBuf, std::ostream& os = std::cerr);
void regfile_dump(std::ostream& os = std::cerr);

class GuestTracer {
#ifdef DBGENA
private:
  using cs_disasm_dl_t = size_t (*)(csh handle, const uint8_t* code,
                                    size_t code_size, uint64_t address,
                                    size_t count, cs_insn** insn);
  using cs_free_dl_t = void (*)(cs_insn* insn, size_t count);
  using cs_open_dl_t = cs_err (*)(cs_arch arch, cs_mode mode, csh* handle);

  cs_disasm_dl_t cs_disasm_dl = nullptr;
  cs_free_dl_t cs_free_dl = nullptr;
  cs_open_dl_t cs_open_dl = nullptr;
  csh handle;

  bool elf_ena;
  ibuf_t instBuf;
  mbuf_t memBuf;
  ebuf_t elfSyms;
  fbuf_t frameStk;

  ureg_t instLatch;
  ureg_t pcLatch;
#endif

  size_t instCnt;

public:
  GuestTracer(const std::string& elf_path)
      : instCnt{0U}
#if DBGENA
      , elf_ena{!elf_path.empty()}
      , instBuf{}
      , memBuf{}
      , elfSyms{}
      , frameStk{}
      , instLatch{0U}
      , pcLatch{0U}
#endif
  {
    init_disasm();
    if (elf_ena) {
      init_elfsym(elf_path);
    }
  }

  void
  dump_print() const {
    const options::DumpPrintOpt& opt = options::error_dump_opt;
    if (opt.reg_file)
      regfile_dump();
    if (opt.inst_buf)
      inst_dump(instBuf);
    if (opt.mem_buf)
      membuf_dump(memBuf);
    if (opt.frame_stk)
      frame_dump(frameStk);
  }

  void
  dump_stats(std::ostream& os = std::cout) const {
    auto mcycles = read_double_csr(MCycleh, MCycle);
    size_t minstret = read_double_csr(MInstreth, MInstret);
    os << std::format("mcycles  {:d}", mcycles) << std::endl;
    os << std::format("minstret {:d}", minstret) << std::endl;
  }

private:
  void init_disasm();
  void disassemble(char* str, int size, uint64_t pc, uint8_t* code,
                   int nbyte);
  void init_elfsym(const std::string& elf_file);
  void frame_trace(uint32_t snpc, uint32_t dst, bool is_ret);

public:
  void inst_trace(uint32_t pc, uint32_t inst);

  size_t
  get_inst_count() const {
    return instCnt;
  }
};

} // namespace trace
