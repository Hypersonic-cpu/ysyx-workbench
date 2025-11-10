#include "disasm.hh"
#include "ccdb.hh"
#include "probe.hh"

#include <cassert>
#include <cstring>
#include <fcntl.h>
#include <cstdlib>
#include <elf.h>
#include <iostream>
#include <iterator>
#include <ostream>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/mman.h>

void 
ccdb::init_disasm() {
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

void 
ccdb::disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte) {
	cs_insn *insn;
	size_t count = cs_disasm_dl(handle, code, nbyte, pc, 0, &insn);
  assert(count == 1);
  int ret = snprintf(str, size, "%s", insn->mnemonic);
  if (insn->op_str[0] != '\0') {
    snprintf(str + ret, size - ret, "\t%s", insn->op_str);
  }
  cs_free_dl(insn, count);
}

void
ccdb::init_elfsym(const char *elf_file) {
  if (elf_file == NULL) { return; }

  int fd = open(elf_file, O_RDONLY);
  assert(fd >= 0 && "Elf file open failed");

  struct stat st;
  int fs_status = fstat(fd, &st);
  std::cerr << "Elf file " << elf_file << ", size = " <<  st.st_size << std::endl;

  uint8_t* map = (uint8_t *) mmap(NULL, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
  assert(map != MAP_FAILED && "Elf mmap failed");

  Elf32_Ehdr* ehdr = (Elf32_Ehdr*) map;
  assert(memcmp(ehdr->e_ident, ELFMAG, SELFMAG) == 0 && 
      "Elf header mismatch");
  assert(ehdr->e_machine == EM_RISCV &&
         "Elf ISA mismatch, not RV32");
  assert(ehdr->e_ident[EI_DATA] == ELFDATA2LSB && 
         "Elf endianess mismatch");

  // Section header
  Elf32_Shdr* shdr = (Elf32_Shdr*) (map + ehdr->e_shoff);
  Elf32_Sym* sym_table = NULL;
  uint8_t* str_table = NULL;
  unsigned sym_count = 0;

  for (unsigned i = 0; i < ehdr->e_shnum; ++i) {
    if (shdr[i].sh_type != SHT_SYMTAB) { continue; }
    sym_table = (Elf32_Sym *) (map + shdr[i].sh_offset);
    sym_count = shdr[i].sh_size / sizeof(Elf32_Sym);
    assert(shdr[i].sh_link < ehdr->e_shnum && 
           "String table out of bound");
    str_table = map + shdr[shdr[i].sh_link].sh_offset;
    break;
  }

  assert(sym_table && "Elf symbol table not found");
  assert(str_table && "Elf string table not found");

  elf_syms.reserve(sym_count);
  for (unsigned i = 0; i < sym_count; ++i) {
    [[maybe_unused]] int bind = ELF32_ST_BIND(sym_table[i].st_info);
    [[maybe_unused]] int type = ELF32_ST_TYPE(sym_table[i].st_info);
    const char*
      sym_name = (const char*) (str_table + sym_table[i].st_name);

    if (type == STT_FUNC) {
      elf_syms.emplace_back(sym_name, 
          /* Address */ sym_table[i].st_value, 
          /* Size */ sym_table[i].st_size);
    }
  }

  std::cerr <<  "\n === ELF Funct Symbols (" << elf_syms.size() << " total) === ";
  std::cerr << std::endl;
  for (auto const& ent : elf_syms) {
    comm::sout32(std::cerr) << ent.addr << " size " << std::dec << ent.size;
    std::cerr << " : " << ent.name << std::endl;
  }

  munmap(map, st.st_size);
  close(fd);
  // TODO: Clear frame stack
}
