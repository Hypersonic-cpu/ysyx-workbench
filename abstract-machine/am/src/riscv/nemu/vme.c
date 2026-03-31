#include "klib-macros.h"
#include <am.h>
#include <klib.h>
#include <nemu.h>
#include <stdint.h>

static AddrSpace kas = {};
static void *(*pgalloc_usr)(int) = NULL;
static void (*pgfree_usr)(void *) = NULL;
static int vme_enable = 0;

static Area segments[] = { // Kernel memory mappings
    NEMU_PADDR_SPACE};

#define USER_SPACE RANGE(0x40000000, 0x80000000)

static inline void set_satp(void *pdir) {
  uintptr_t mode = 1ul << (__riscv_xlen - 1);
  asm volatile("csrw satp, %0" : : "r"(mode | ((uintptr_t)pdir >> 12)));
}

static inline uintptr_t get_satp() {
  uintptr_t satp;
  asm volatile("csrr %0, satp" : "=r"(satp));
  return satp << 12;
}

bool vme_init(void *(*pgalloc_f)(int), void (*pgfree_f)(void *)) {
  pgalloc_usr = pgalloc_f;
  pgfree_usr = pgfree_f;

  // Alloc Kernel AddrSpace page table here
  kas.ptr = pgalloc_f(PGSIZE);

  int i;
  for (i = 0; i < LENGTH(segments); i++) {
    void *va = segments[i].start;
    for (; va < segments[i].end; va += PGSIZE) {
      map(&kas, va, va, 0);
    }
  }

  set_satp(kas.ptr);
  vme_enable = 1;

  return true;
}

void protect(AddrSpace *as) {
  PTE *updir = (PTE *)(pgalloc_usr(PGSIZE));
  as->ptr = updir;
  as->area = USER_SPACE;
  as->pgsize = PGSIZE;
  // map kernel space
  memcpy(updir, kas.ptr, PGSIZE);
}

void unprotect(AddrSpace *as) {}

void __am_get_cur_as(Context *c) {
  c->pdir = (vme_enable ? (void *)get_satp() : NULL);
}

void __am_switch(Context *c) {
  if (vme_enable && c->pdir != NULL) {
    set_satp(c->pdir);
  }
}

#if defined(__riscv) && __riscv_xlen == 32
/**
 * 34-bit physical address [ 12-bit PPN1 | 10-bit PPN0 | 12-bit offset ]
 * 32-bit virtual address  [ 10-bit VPN1 | 10-bit VPN0 | 12-bit offset ]
 * L1/0 Page Table Entry   [ 22-bit PPN  | 2-bit RSW | D A G U X W R V ]
 * Leaf PTE: RWX != 0b000
 */
#define VPN0(addr) (((uint32_t)(addr) >> 12) & 0x3ffU)
#define VPN1(addr) (((uint32_t)(addr) >> 22) & 0x3ffU)
// NOTE: u32 for 32-bit XLEN of bus. Should be u34.
#define PPN(addr) (((uint32_t)(addr) >> 12) & 0x3fffffU)
#define PP_ADDR(addr) (((uint32_t)(addr) >> 10) << 12)
// #define PPN0(addr) (((uint32_t)(addr) >> 10) & 0x3ffU)
// #define PPN1(addr) (((uint32_t)(addr) >> 20) & 0xfffU)
#define OFFSET(addr) ((uint32_t)(addr) & 0xfffU)
#else
#error "Unsupported architecture RV64"
#endif

void map(AddrSpace *as, void *va, void *pa, int prot) {
  as->pgsize = PGSIZE;
  PTE *ptable = as->ptr;
  PTE *pptr1 = &ptable[VPN1(va)];
  if (!((*pptr1) & PTE_V)) {
    // Invalid table [1], allocate a new one
    PTE *ptable1 = (PTE *)pgalloc_usr(PGSIZE);
    *pptr1 = (PPN(ptable1) << 10) | PTE_V;
  }

  PTE *ptable1 = (PTE *)(PP_ADDR(*pptr1));
  PTE *leaf = &ptable1[VPN0(va)];
  // Currently do not support protection
  prot |= PTE_R | PTE_W | PTE_X;
  *leaf = (PPN(pa) << 10) | PTE_V | prot;
  // printf("Map VA %08x VPN1 %8x VPN2 %8x pTable1 Addr %08x Val %08x pTable0 "
  //        "Addr %08x Val %08x\n",
  //        (uintptr_t)va, VPN1(va), VPN0(va), (uintptr_t)ptable, *pptr1,
  //        (uintptr_t)ptable1, *leaf);
}

Context *ucontext(AddrSpace *as, Area kstack, void *entry) {
  // U Context itself is on kernel stack
  Context *ctx = kstack.end - sizeof(Context);
  for (size_t i = 0; i < sizeof(Context) / sizeof(uintptr_t); i++) {
    * ((uintptr_t *) ctx + i) = 0xBadC0DE;
  }
  // ctx->gpr[10] = (uintptr_t) as;
  ctx->gpr[ 2] = (uintptr_t) as->area.end;
  ctx->mepc = (uintptr_t) entry;
  ctx->mstatus = 0x80;
  ctx->pdir = as->ptr;
  return ctx;
}
