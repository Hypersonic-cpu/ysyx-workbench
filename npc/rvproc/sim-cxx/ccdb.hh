#pragma once 

#include <array>
#include <cstdint>
// #include <algorithm>
// #include <format>
// #include <iomanip>
// #include <iostream>
// #include <iterator>
// #include <list>
// #include <ostream>
// #include <regex>
// #include <stack>
#include <cstdio>
#include <elf.h>
#include <fcntl.h>
#include <format>
#include <limits>
#include <list>
#include <stdexcept>
#include <capstone/capstone.h>
#include <cassert>
#include <cstdlib>
#include <dlfcn.h>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <vector>
// #include <string>
// #include <utility>
// #include <vector>

#include "VysyxSoCFull.h"
#include "VysyxSoCFull___024root.h"

// #include "disasm.hh"
#include "probe.hh"
#include "options.hh"

namespace trace {
  using ptop_t = const TOP_NAME*;
  extern ptop_t ptop;

  // 0x10 for PC
  inline ureg_t
  read_reg(uint8_t regid) {
    auto r = ptop->rootp;
    ureg_t ret = 0;
    switch (regid) {
      case 0x0: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_0; break;
      case 0x1: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_1; break;
      case 0x2: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_2; break;
      case 0x3: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_3; break;
      case 0x4: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_4; break;
      case 0x5: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_5; break;
      case 0x6: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_6; break;
      case 0x7: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_7; break;
      case 0x8: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_8; break;
      case 0x9: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_9; break;
      case 0xa: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_10; break;
      case 0xb: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_11; break;
      case 0xc: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_12; break;
      case 0xd: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_13; break;
      case 0xe: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_14; break;
      case 0xf: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__gpr__DOT__gprs_15; break;
      case 0x10:ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__ifs__DOT__pc; break;
      default: throw std::runtime_error(
                   "Invalid GPR read @ regid = " + std::to_string(regid)); 
               break;
    }
    return ret;
  }

  inline ureg_t
  read_inst_latch() {
    return ptop->rootp->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__ifs__DOT__instLatch;
  }

  enum IFState { Idle = 0, Serve, Hold, Start };

  inline IFState 
  read_ifs_state() {
    auto r = ptop->rootp;
    uint8_t val = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__ifs__DOT__state & 0b11;
    return IFState(val);
  }

  extern IFState last_state;
  inline bool
  npc_inst_commit() {
    return read_ifs_state() == Serve && last_state == Idle;
  }

  inline void 
  upd_ifs_mcstate() { last_state = read_ifs_state(); }

  constexpr std::array<const char*, 4> csr_list {
    "mtvec", "mepc", "mstatus", "mcause"
  };

  enum CsrSel { 
    MTvec = 0, MEpc, MStatus, MCause, 
    MCycle, MCycleh, MInstret, MInstreth,
    Num_CsrSel
  };

  inline ureg_t
  read_csr(CsrSel fakeid) {
    auto r = ptop->rootp;
    ureg_t ret = 0;
    switch (fakeid) {
      case MTvec:    ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__csr__DOT__mtvec    ; break;
      case MEpc:     ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__csr__DOT__mepc     ; break;
      case MStatus:  ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__csr__DOT__mstatus  ; break;
      case MCause:   ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__csr__DOT__mcause   ; break;
      case MCycle:   ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__csr__DOT__mcycle   ; break;
      case MCycleh:  ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__csr__DOT__mcycleh  ; break;
      case MInstret: ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__csr__DOT__minstret ; break;
      case MInstreth:ret = r->ysyxSoCFull__DOT__asic__DOT__cpu__DOT__cpu__DOT__reg_0__DOT__csr__DOT__minstreth; break;
      default: throw std::runtime_error("Out-of-range CSR read"); break;
    }
    return ret;
  }

  inline size_t
  read_double_csr(CsrSel hi, CsrSel lo) {
    size_t ret = read_csr(hi);
    ret <<= 32;
    ret |= read_csr(lo);
    return ret;
  }

  void elftable_dump(const ebuf_t& elfSyms, std::ostream& os=std::cerr);
  void inst_dump(const ibuf_t& instBuf, std::ostream& os = std::cerr);
  void frame_dump(const fbuf_t& frameStk, std::ostream& os = std::cerr);
  void membuf_dump(const mbuf_t& memBuf, std::ostream& os = std::cerr);
  void regfile_dump(std::ostream& os = std::cerr);

  template <bool E>
  class GuestTracer {
  private:
    using cs_disasm_dl_t = size_t (*)(csh handle, const uint8_t *code,
        size_t code_size, uint64_t address,
        size_t count, cs_insn **insn);
    using cs_free_dl_t = void (*)(cs_insn *insn, size_t count);
    using cs_open_dl_t = cs_err (*)(cs_arch arch, cs_mode mode, csh *handle);

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

// void mem_acc_log(uint32_t addr, bool is_write, uint32_t data,
//                  uint8_t byte_mask);
  // memBuf.append(MemEnt{addr, is_write, data, byte_mask}).printent(std::cerr);
  public:
    GuestTracer(const std::string& elf_path)
      : elf_ena{ !elf_path.empty() }
      , instBuf{}
      , memBuf{}
      , elfSyms{}
      , frameStk{}
      , instLatch{ 0U }
      , pcLatch{ 0U }
      , instCnt{ 0U }
      // , pcjmp(-4096, +4096, 64, options::ResetVector)
      , ifcyc(0, 30, 2, 150)
      , lscyc(0, 30, 2, std::numeric_limits<uint64_t>::max())
    {
      if constexpr (!E) {
        return;
      }
      init_disasm();
      if (elf_ena) { init_elfsym(elf_path); }
    }

    void
    dump_print() const {
      if constexpr (!E) return;
      const options::DumpPrintOpt& opt = options::error_dump_opt;
      if (opt.reg_file)   regfile_dump();
      if (opt.inst_buf)   inst_dump   (instBuf );
      if (opt.mem_buf)    membuf_dump (memBuf  );
      if (opt.frame_stk)  frame_dump  (frameStk);
    }

    void
    dump_stats() const {
      if constexpr (!E) return;
      auto mcycles = read_double_csr(MCycleh, MCycle);
      size_t minstret = read_double_csr(MInstreth, MInstret);
      std::cerr << std::format("mcycles  {:d}", mcycles ) << std::endl;
      std::cerr << std::format("minstret {:d}", minstret) << std::endl;
      
      // auto const pcdelta_name = pcjmp.get_indices();
      // auto const pcdelta_data = pcjmp.get_stats();
      // for (size_t i = 0; i < pcdelta_name.size(); i++) {
      //   std::cerr << std::format("{}\t: {:16d}", pcdelta_name[i], pcdelta_data[i]) << std::endl;
      // }
      auto const iftime_name  = ifcyc.get_indices();
      auto const iftime_data  = ifcyc.get_stats();
      std::cout << "Inst Fetch Distri (Cyc) : " << std::dec 
        << ifcyc.get_total() << " total" << std::endl;
      for (size_t i = 0; i < iftime_name.size(); i++) {
        std::cout << std::format("{}\t: {:16d}", iftime_name[i], iftime_data[i]) << std::endl;
      }
      auto const lstime_name  = lscyc.get_indices();
      auto const lstime_data  = lscyc.get_stats();
      std::cout << "Load Store Distri (Cyc) : " << std::dec 
        << lscyc.get_total() << " total" << std::endl;
      for (size_t i = 0; i < lstime_name.size(); i++) {
        std::cout << std::format("{}\t: {:16d}", lstime_name[i], lstime_data[i]) << std::endl;
      }
    }

  private:
    void
    init_disasm() {
      auto nemu_path = std::string(std::getenv("NEMU_HOME"));
      void *dl_handle;
      dl_handle = dlopen(
          (nemu_path + std::string("/tools/capstone/repo/libcapstone.so.5")).c_str(),
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
    disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte) {
      if constexpr (!E) {
        return;
      }
      cs_insn *insn;
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
    
      uint8_t *map =
        (uint8_t *)mmap(NULL, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
      assert(map != MAP_FAILED && "Elf mmap failed");

      Elf32_Ehdr *ehdr = (Elf32_Ehdr *)map;
      assert(memcmp(ehdr->e_ident, ELFMAG, SELFMAG) == 0 && "Elf header mismatch");
      assert(ehdr->e_machine == EM_RISCV && "Elf ISA mismatch, not RV32");
      assert(ehdr->e_ident[EI_DATA] == ELFDATA2LSB && "Elf endianess mismatch");

      // Section header
      Elf32_Shdr *shdr = (Elf32_Shdr *)(map + ehdr->e_shoff);
      Elf32_Sym *sym_table = NULL;
      uint8_t *str_table = NULL;
      unsigned sym_count = 0;
    
      for (unsigned i = 0; i < ehdr->e_shnum; ++i) {
        if (shdr[i].sh_type != SHT_SYMTAB) {
          continue;
        }
        sym_table = (Elf32_Sym *)(map + shdr[i].sh_offset);
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
        const char *sym_name = (const char *)(str_table + sym_table[i].st_name);
    
        uint32_t addr = sym_table[i].st_value;
    
        if (type == STT_FUNC) {
          v_warn(elfSyms.find(addr) == elfSyms.end(),
                       "Multiple symbols at the same addr ", addr, " name ",
                       sym_name, " and ", elfSyms.find(addr)->second.name);
          elfSyms.try_emplace(addr,
                               /* Name */ sym_name,
                               /* Address */ sym_table[i].st_value,
                               /* Size */ sym_table[i].st_size
          );
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
    inst_trace() {
      if constexpr (!E) return;
      if (read_ifs_state() == IFState::Hold) {
        pcLatch = read_reg(RegNum);
        instLatch = read_inst_latch();
        return;
      }
      if (!npc_inst_commit()) return;
      instCnt++;
      auto pc = pcLatch;
      auto inst = instLatch;

      constexpr size_t BufferLen{256U};
      char buf[BufferLen] = {0};
      disassemble(buf, BufferLen, pc, (uint8_t *)(&inst), 4);

      auto ent = InstEnt{pc, inst, buf};
      instBuf.append(ent);
      if (options::runtime_dump_opt.inst_buf) {
        ent.printent(std::cerr);
      }

      using util::bits, util::sext;
      bool is_jalr = bits(inst, 6, 2) == 0b11001;
      bool is_jal  = bits(inst, 6, 2) == 0b11011;
      if (is_jalr || is_jal) {
        uint8_t rd = bits(inst, 11, 7);
        uint8_t rs1 = bits(inst, 19, 15);
        uint32_t immI = sext(bits(inst, 31, 20), 12);
        uint32_t immJ = sext(
          (bits(inst, 31, 31) << 20) | (bits(inst, 19, 12) << 12) |
            (bits(inst, 20, 20) << 11) | (bits(inst, 30, 21) << 1),
          21);

        auto src1 = is_jalr ? read_reg(rs1) : 0;
        auto dst = is_jalr ? ((immI + src1) & (~1U)) : (immJ + pc);
        // Check ELF symbol for pc / dst
        frame_trace(pc + 4, dst, rd == 0);
      }
    }

    size_t
    get_inst_count() const { return instCnt; }

    /* PMU */
  private:

    template<typename T>
    class Distri {
      protected:
        T min;
        T max;
        T delta;
        T maxidx;
        std::vector<size_t> arr;
        size_t total;
      public:
        Distri(T min, T max, T delta)
          : min{ min }
          , max{ max }
          , delta{ delta }
          , maxidx{ (max-min) / delta }
          , arr( maxidx + 5, 0U)
          , total{ 0 }
          {}

        virtual void sample(T v) {
          total++;
          if (v > max) { arr.at(maxidx + 1) += 1; }
          else if (v < min) { arr.at(maxidx + 2) += 1; }
          else { 
            auto idx = (v - min) / delta;
            arr.at(idx) += 1;
          }
          arr.at(maxidx + 3) = std::max<T>(v, arr[maxidx + 3]);
          arr.at(maxidx + 4) = std::min<T>(v, arr[maxidx + 4]);
        }

        const std::vector<size_t>&
        get_stats() const {
          return arr;
        }

        std::vector<std::string>
        get_indices() const {
          std::vector<std::string> ret(maxidx+5);
          for (auto i = 0U; i <= maxidx; i++) {
            ret.at(i) = std::to_string(min + delta * i);
          }
          ret.at(maxidx + 1) = "overflow";
          ret.at(maxidx + 2) = "underflow";
          ret.at(maxidx + 3) = "maximum";
          ret.at(maxidx + 4) = "minimum";
          return std::move(ret);
        }
        
        size_t
        get_total() const { return total; }
    };

    template<typename T>
    class DeltaDistri : public Distri<T> {
      private:
        T last;
      public:
        DeltaDistri(T min, T max, T delta, T init)
          : Distri<T>(min, max, delta)
          , last{ init } {}

        void sample(T v) override {
          auto dpc = v - last;
          // fprintf(stderr, "sample  from %8x to %8x\n", last, v);
          Distri<T>::sample(dpc);
          last = v;
        }

        void updlast(T v) {
          // fprintf(stderr, "updlast from %8x to %8x\n", last, v);
          last = v;
        }
    };

    // DeltaDistri<int64_t> pcjmp;
    DeltaDistri<uint64_t> ifcyc;
    DeltaDistri<uint64_t> lscyc;

  public:
    void notifyIFIssue(addr_t pc) {
      ifcyc.sample(read_double_csr(MCycleh, MCycle));
      // pcjmp.sample(static_cast<int64_t>(pc));
    }
    void notifyIFFetch(addr_t pc) {
      ifcyc.updlast(read_double_csr(MCycleh, MCycle));
    }
    void notifyLSReq(addr_t a) {
      // fprintf(stderr, "req at %x\n", read_double_csr(MCycleh, MCycle));
      lscyc.updlast(read_double_csr(MCycleh, MCycle));
    }
    void notifyLSResp(addr_t a) {
      // fprintf(stderr, "resp at %x\n", read_double_csr(MCycleh, MCycle));
      lscyc.sample(read_double_csr(MCycleh, MCycle));
    }
  };
}
