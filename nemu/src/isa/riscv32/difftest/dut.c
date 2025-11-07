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

#include <isa.h>
#include <cpu/difftest.h>
#include "../local-include/reg.h"
#include "utils.h"
#include "common.h"

bool isa_difftest_checkregs(CPU_state *ref_r, vaddr_t pc, vaddr_t dnpc) {
  unsigned const regnum = MUXDEF(CONFIG_RVE, 16, 32);
  bool success = true;
  // set to -1 to skip check
  if (dnpc != (vaddr_t) (-1) && ref_r->pc != dnpc) {
    fprintf(stderr, ANSI_FG_RED 
            "DiffTest NextPC mismatch @ PC " FMT_WORD ": " 
            "ref " FMT_WORD " got " FMT_WORD "\n" ANSI_NONE,
            pc, ref_r->pc, dnpc);
    success = false;
  }
  for (unsigned i = 0; i < regnum; ++i) {
    if (ref_r->gpr[i] == gpr(i)) { continue; }
    if (success) {
      fprintf(stderr, ANSI_FG_RED 
              "DiffTest @ PC = " FMT_WORD ": reg state mismatch\n" ANSI_NONE,
              pc);
    }
    fprintf(stderr, ANSI_FG_RED 
            "GPR[%2u] ref " FMT_WORD " got " FMT_WORD "\n" ANSI_NONE, 
            i, ref_r->gpr[i], gpr(i)
            );
    success = false;
  }
  return success;
}

void isa_difftest_attach() {
}
