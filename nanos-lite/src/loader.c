#include "fs.h"
#include <elf.h>
#include <proc.h>

#ifdef __LP64__
#define Elf_Ehdr Elf64_Ehdr
#define Elf_Phdr Elf64_Phdr
#define Elf_Shdr Elf64_Shdr
#else
#define Elf_Ehdr Elf32_Ehdr
#define Elf_Phdr Elf32_Phdr
#define Elf_Shdr Elf32_Shdr
#endif

__attribute__((used)) static const char *ph_type_str(uint32_t type) {
  switch (type) {
  case 0:
    return "PT_NULL";
  case 1:
    return "PT_LOAD";
  case 2:
    return "PT_DYNAMIC";
  case 3:
    return "PT_INTERP";
  case 4:
    return "PT_NOTE";
  case 6:
    return "PT_PHDR";
  default:
    return "PT_UNKNOWN";
  }
}

static uintptr_t loader(PCB *pcb, const char *filename) {
  int fd = fs_open(filename, 0, 0);
  assert(fd >= 3);

  Elf_Ehdr ehdr;
  fs_read(fd, &ehdr, sizeof(ehdr));

  // Magic number check
  assert(ehdr.e_ident[0] == 0x7f && ehdr.e_ident[1] == 'E' &&
         ehdr.e_ident[2] == 'L' && ehdr.e_ident[3] == 'F');

  Log("ELF entry = %p, phoff = %u, phnum = %u", (void *)ehdr.e_entry,
      ehdr.e_phoff, ehdr.e_phnum);

  Elf_Phdr phdrs[ehdr.e_phnum];
  fs_lseek(fd, ehdr.e_phoff, SEEK_SET);
  fs_read(fd, phdrs, ehdr.e_phnum * sizeof(Elf_Phdr));

#if LOADER_DBG
  Elf_Shdr shdrs[ehdr.e_shnum];
  fs_lseek(fd, ehdr.e_shoff, SEEK_SET);
  fs_read(fd, shdrs, ehdr.e_shnum * sizeof(Elf_Shdr));

  Elf_Shdr *shstrtab_hdr = &shdrs[ehdr.e_shstrndx];
  char shstrtab[shstrtab_hdr->sh_size];
  fs_lseek(fd, shstrtab_hdr->sh_offset, SEEK_SET);
  ramdisk_read(fd, shstrtab, shstrtab_hdr->sh_size);
#endif

  // For each header
  for (uint16_t i = 0; i < ehdr.e_phnum; i++) {
    Elf_Phdr *hdr = &phdrs[i];

#if LOADER_DBG
    Log("Segment [%d] type=%s vaddr=0x%x ==>", i, ph_type_str(hdr->p_type),
        hdr->p_vaddr);

    for (int j = 0; j < ehdr.e_shnum; j++) {
      Elf_Shdr *sh = &shdrs[j];
      // section 的地址落在 segment 的 [vaddr, vaddr+memsz) 范围内
      if (sh->sh_addr >= hdr->p_vaddr &&
          sh->sh_addr < hdr->p_vaddr + hdr->p_memsz) {
        Log("  section: %16s addr=0x%x size=0x%x", &shstrtab[sh->sh_name],
            sh->sh_addr, sh->sh_size);
      }
    }
#endif

    if (hdr->p_type != PT_LOAD)
      continue;

    Log("> Copy offset = %08x to vaddr = %08x", hdr->p_offset, hdr->p_vaddr);

    // Alloc target space
    void *tar = (void *)hdr->p_vaddr;
    fs_lseek(fd, hdr->p_offset, SEEK_SET);
    fs_read(fd, tar, hdr->p_filesz);
    if (hdr->p_memsz > hdr->p_filesz) {
      memset(tar + hdr->p_filesz, 0, hdr->p_memsz - hdr->p_filesz);
    }
  }

  return ehdr.e_entry;
}

void naive_uload(PCB *pcb, const char *filename) {
  uintptr_t entry = loader(pcb, filename);
  Log("Jump to entry = %p", (void *)entry);
  ((void (*)())entry)();
}
