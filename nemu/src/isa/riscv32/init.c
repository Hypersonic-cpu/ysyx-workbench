/***************************************************************************************
* Copyright (c) 2014-2024 Zihao Yu, Nanjing University
*
* NEMU is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

#include "common.h"
#include <isa.h>
#include <memory/paddr.h>

#include <elf.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/cdefs.h>
#include <sys/stat.h>
#include <fcntl.h>

// this is not consistent with uint8_t
// but it is ok since we do not access the array directly
static const uint32_t img [] = {
  0x00000297,  // auipc t0,0
  0x00028823,  // sb  zero,16(t0)
  0x0102c503,  // lbu a0,16(t0)
  0x00100073,  // ebreak (used as nemu_trap)
  0xdeadbeef,  // some data
};

static void restart() {
  /* Set the initial program counter. */
  cpu.pc = RESET_VECTOR;

  /* The zero register is always 0. */
  cpu.gpr[0] = 0;
}

void init_isa() {
  /* Load built-in image. */
  memcpy(guest_to_host(RESET_VECTOR), img, sizeof(img));

  /* Initialize this virtual computer system. */
  restart();
}

// INIT_ELF
void init_elf(const char* elf_file) {
  if (elf_file == NULL) { return; }

  int fd = open(elf_file, O_RDONLY);
  Assert(fd >= 0, "Can not open '%s'", elf_file);

  struct stat st;
  int fs_status = fstat(fd, &st);
  Assert(fs_status == 0, "Cannot get size of elf '%s'", elf_file);
  Log("The elf is %s, size = %ld", elf_file, st.st_size);

  uint8_t* map = (uint8_t *) mmap(NULL, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
  Assert(map != MAP_FAILED, "Elf '%s' mmap failed", elf_file);

  Elf32_Ehdr* ehdr = (Elf32_Ehdr*) map;
  Assert(memcmp(ehdr->e_ident, ELFMAG, SELFMAG) == 0,
         "Elf header mismatch");
  Assert(ehdr->e_machine == EM_RISCV,
         "Elf ISA mismatch, not RV32");
  Assert(ehdr->e_ident[EI_DATA] == ELFDATA2LSB, 
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
    Assert(shdr[i].sh_link < ehdr->e_shnum, 
           "String table out of bound");
    str_table = map + shdr[shdr[i].sh_link].sh_offset;
    break;
  }

  Assert(sym_table, "Elf symbol table not found");
  Assert(str_table, "Elf string table not found");

  printf(" === ELF Symbol Table (%u total) === \n", sym_count);
  for (unsigned i = 0; i < sym_count; ++i) {
    __attribute_maybe_unused__ int bind = ELF32_ST_BIND(sym_table[i].st_info);
    __attribute_maybe_unused__ int type = ELF32_ST_TYPE(sym_table[i].st_info);

    if (type == STT_FUNC)
    printf("[%3u] 0x%8x: %s\n" , i, 
           (vaddr_t) sym_table[i].st_value, 
           (const char*) (str_table + sym_table[i].st_name)
           );

  }
}

