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
#include <list>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unordered_map>

#include "options.hh"
#include "probe.hh"

namespace trace {

void elftable_dump(const ebuf_t& elfSyms, std::ostream& os = std::cerr);
void inst_dump(const ibuf_t& instBuf, std::ostream& os = std::cerr);
void frame_dump(const fbuf_t& frameStk, std::ostream& os = std::cerr);
void membuf_dump(const mbuf_t& memBuf, std::ostream& os = std::cerr);
void regfile_dump(std::ostream& os = std::cerr);

class GuestTracer {
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

  size_t instCnt;

public:
  GuestTracer(const std::string& elf_path)
      : elf_ena{!elf_path.empty()}
      , instBuf{}
      , memBuf{}
      , elfSyms{}
      , frameStk{}
      , instLatch{0U}
      , pcLatch{0U}
      , instCnt{0U}
  {
    if constexpr (!options::gdbg_enable) {
      return;
    }
    init_disasm();
    if (elf_ena) {
      init_elfsym(elf_path);
    }
  }

  void
  dump_print() const {
    if constexpr (!options::gdbg_enable)
      return;
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
    if constexpr (!options::gdbg_enable)
      return;
    auto mcycles = read_double_csr(MCycleh, MCycle);
    size_t minstret = read_double_csr(MInstreth, MInstret);
    os << std::format("mcycles  {:d}", mcycles) << std::endl;
    os << std::format("minstret {:d}", minstret) << std::endl;
  }

private:
  void
  init_disasm() {
    auto nemu_path = std::string(std::getenv("NEMU_HOME"));
    void* dl_handle;
    dl_handle = dlopen(
      (nemu_path + std::string("/tools/capstone/repo/libcapstone.so.5"))
        .c_str(),
      RTLD_LAZY);
    assert(dl_handle);

    cs_open_dl = (cs_open_dl_t)dlsym(dl_handle, "cs_open");
    cs_disasm_dl = (cs_disasm_dl_t)dlsym(dl_handle, "cs_disasm");
    cs_free_dl = (cs_free_dl_t)dlsym(dl_handle, "cs_free");
    assert(cs_open_dl);
    assert(cs_disasm_dl);
    assert(cs_free_dl);
    cs_arch arch = CS_ARCH_RISCV;
    cs_mode mode = cs_mode(CS_MODE_RISCV32 | CS_MODE_RISCVC);
    [[maybe_unused]] int ret = cs_open_dl(arch, mode, &handle);
    assert(ret == CS_ERR_OK);
  }

  void
  disassemble(char* str, int size, uint64_t pc, uint8_t* code, int nbyte) {
    if constexpr (!options::gdbg_enable) {
      return;
    }
    cs_insn* insn;
    size_t count = cs_disasm_dl(handle, code, nbyte, pc, 0, &insn);
    v_assert(count == 1, "Failed to disassemble `", str, "'");
    int ret = snprintf(str, size, "%s", insn->mnemonic);
    if (insn->op_str[0] != '\0') {
      snprintf(str + ret, size - ret, "\t%s", insn->op_str);
    }
    cs_free_dl(insn, count);
  }

  void
  init_elfsym(const std::string& elf_file) {
    if (options::runtime_dump_opt.elf_symbol)
      std::cerr << "Elf file " << elf_file;
    int fd = open(elf_file.c_str(), O_RDONLY);
    assert(fd >= 0 && "Elf file open failed");

    struct stat st;
    int fs_status = fstat(fd, &st);
    if (options::runtime_dump_opt.elf_symbol)
      std::cerr << ", size = " << st.st_size << std::endl;

    uint8_t* map =
      (uint8_t*)mmap(NULL, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    assert(map != MAP_FAILED && "Elf mmap failed");

    Elf32_Ehdr* ehdr = (Elf32_Ehdr*)map;
    assert(memcmp(ehdr->e_ident, ELFMAG, SELFMAG) == 0 &&
           "Elf header mismatch");
    assert(ehdr->e_machine == EM_RISCV && "Elf ISA mismatch, not RV32");
    assert(ehdr->e_ident[EI_DATA] == ELFDATA2LSB &&
           "Elf endianess mismatch");

    // Section header
    Elf32_Shdr* shdr = (Elf32_Shdr*)(map + ehdr->e_shoff);
    Elf32_Sym* sym_table = NULL;
    uint8_t* str_table = NULL;
    unsigned sym_count = 0;

    for (unsigned i = 0; i < ehdr->e_shnum; ++i) {
      if (shdr[i].sh_type != SHT_SYMTAB) {
        continue;
      }
      sym_table = (Elf32_Sym*)(map + shdr[i].sh_offset);
      sym_count = shdr[i].sh_size / sizeof(Elf32_Sym);
      assert(shdr[i].sh_link < ehdr->e_shnum && "String table out of bound");
      str_table = map + shdr[shdr[i].sh_link].sh_offset;
      break;
    }

    assert(sym_table && "Elf symbol table not found");
    assert(str_table && "Elf string table not found");

    elfSyms.reserve(sym_count);
    for (unsigned i = 0; i < sym_count; ++i) {
      [[maybe_unused]] int bind = ELF32_ST_BIND(sym_table[i].st_info);
      [[maybe_unused]] int type = ELF32_ST_TYPE(sym_table[i].st_info);
      const char* sym_name = (const char*)(str_table + sym_table[i].st_name);

      uint32_t addr = sym_table[i].st_value;

      if (type == STT_FUNC) {
        v_warn(elfSyms.find(addr) == elfSyms.end(),
               "Multiple symbols at the same addr ", addr, " name ",
               sym_name, " and ", elfSyms.find(addr)->second.name);
        elfSyms.try_emplace(addr,
                            /* Name */ sym_name,
                            /* Address */ sym_table[i].st_value,
                            /* Size */ sym_table[i].st_size);
      }
    }

    if (options::runtime_dump_opt.elf_symbol)
      elftable_dump(elfSyms);

    munmap(map, st.st_size);
    close(fd);
  }

  void
  frame_trace(uint32_t snpc, uint32_t dst, bool is_ret) {
    auto it = elfSyms.find(dst);
    auto const read_args = []() {
      std::array<uint32_t, FunctArgs> aret{};
      for (size_t i = 0; i < FunctArgs; i++) {
        aret.at(i) = read_reg(10U + i);
      }
      return aret;
    };

    if (is_ret && it != elfSyms.end()) { // TCO
      // Jump to a symbol, with rd == 0,
      // TCO psuedo ret of current frame.
      unsigned depth = 0;
      unsigned ra = 0;
      if (frameStk.empty()) {
        std::cerr << "TCO on empty frame stack, change to simply alloc"
                  << std::endl;
      } else {
        auto temp = frameStk.front();
        depth = temp.depth;
        ra = temp.ra;
        if (options::runtime_dump_opt.frame_stk)
          frameStk.front().printent(std::cerr, "- [TCO]", true);
        frameStk.pop_front();
      }
      // Alloc new frame, but ra remains.
      frameStk.emplace_front(depth, it->second.name, it->second.addr, ra,
                             read_args());
      if (options::runtime_dump_opt.frame_stk)
        frameStk.front().printent(std::cerr, "+", true);
    } else if (it != elfSyms.end()) {
      auto depth = frameStk.empty() ? 0U : (frameStk.front().depth + 1);
      // The static NPC (PC of jal +4) is ra
      frameStk.emplace_front(depth, it->second.name, it->second.addr, snpc,
                             read_args());
      if (options::runtime_dump_opt.frame_stk)
        frameStk.front().printent(std::cerr, "+", true);
    } else if (is_ret) {
      auto ir = frameStk.begin();
      for (; ir != frameStk.end(); ir++) {
        if (ir->ra == dst) {
          // Jump back => true ret.
          break;
        }
      }
      if (ir == frameStk.end()) {
        return;
      } else {
        if (options::runtime_dump_opt.frame_stk)
          frameStk.front().printent(std::cerr, "-", true);
        // Should not skip !
        assert(&(*ir) == &frameStk.front());
        frameStk.pop_front();
      }
    }
  }

public:
  void
  inst_trace(uint32_t pc, uint32_t inst) {
    if constexpr (!options::gdbg_enable)
      return;
    instCnt++;

    constexpr size_t BufferLen{256U};
    char buf[BufferLen] = {0};
    disassemble(buf, BufferLen, pc, (uint8_t*)(&inst), 4);

    auto ent = InstEnt{pc, inst, buf};
    instBuf.append(ent);
    if (options::runtime_dump_opt.inst_buf) {
      ent.printent(std::cerr);
    }

    using util::bits, util::sext;
    bool is_jalr = bits(inst, 6, 2) == 0b11001;
    bool is_jal = bits(inst, 6, 2) == 0b11011;
    if (is_jalr || is_jal) {
      uint8_t rd = bits(inst, 11, 7);
      uint8_t rs1 = bits(inst, 19, 15);
      uint32_t immI = sext(bits(inst, 31, 20), 12);
      uint32_t immJ =
        sext((bits(inst, 31, 31) << 20) | (bits(inst, 19, 12) << 12) |
               (bits(inst, 20, 20) << 11) | (bits(inst, 30, 21) << 1),
             21);

      auto src1 = is_jalr ? read_reg(rs1) : 0;
      auto dst = is_jalr ? ((immI + src1) & (~1U)) : (immJ + pc);
      // Check ELF symbol for pc / dst
      frame_trace(pc + 4, dst, rd == 0);
    }
  }

  size_t
  get_inst_count() const {
    return instCnt;
  }
};

} // namespace trace
