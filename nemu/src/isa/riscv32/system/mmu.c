/***************************************************************************************
 * Copyright (c) 2014-2024 Zihao Yu, Nanjing University
 *
 * NEMU is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan
 *PSL v2. You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY
 *KIND, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 *NON-INFRINGEMENT, MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 *
 * See the Mulan PSL v2 for more details.
 ***************************************************************************************/

#include "debug.h"
#include "isa-def.h"
#include <isa.h>
#include <memory/paddr.h>
#include <memory/vaddr.h>
#include <stdio.h>

#define PTE_PA(x) (((x) >> 10) << 12)
#define OFFSET(x) ((x) & 0xfffU)
paddr_t translate(vaddr_t vaddr, paddr_t ptable, int level) {
  paddr_t vpn = (vaddr >> (level == 1 ? 22 : 12)) & 0x3ffU;
  paddr_t pte = paddr_read(ptable + vpn * sizeof(paddr_t), sizeof(paddr_t));
  if ((pte & 1) == 0)
    return MEM_RET_FAIL;
  if (level == 1) {
    Assert((pte & 0b1110) == 0, "L1 page table " FMT_WORD " should have RWX==0",
           pte);
  } else {
    Assert(pte & 0b1110, "L0 page table " FMT_WORD " should have RWX!=0", pte);
  }
  // printf(" Translate[L%d] for VA " FMT_WORD ": PTable " FMT_WORD " Value " FMT_WORD "\n", level, vaddr, ptable, pte);
  if (pte & 0b1110) {
    return PTE_PA(pte) | OFFSET(vaddr);
  } else {
    paddr_t nxt_ptable = PTE_PA(pte);
    return translate(vaddr, nxt_ptable, level - 1);
  }
}

paddr_t isa_mmu_translate(vaddr_t vaddr, int len, int type) {
  paddr_t ptable0 = (csr_read(RISCV_CSR_SATP) & 0xfffffU) << 12;
  // printf(" Translate for VA " FMT_WORD ": PTable " FMT_WORD "\n", vaddr, ptable0);
  // return translate(vaddr, ptable0, 1);
  paddr_t ret = translate(vaddr, ptable0, 1);
  // printf(" Translate for VA " FMT_WORD " -> PA " FMT_WORD "\n", vaddr, ret);
  Assert(ret == vaddr, "translated %08x != vaddr %08x", ret, vaddr);
  return ret;
}
