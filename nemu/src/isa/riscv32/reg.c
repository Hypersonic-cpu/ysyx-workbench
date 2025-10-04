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
#include <stdio.h>
#include "local-include/reg.h"

const char *regs[] = {
  // "$0",
  // WARN: Use ABI name for x0.
  "zero", "ra", "sp", "gp", "tp", "t0", "t1", "t2",
  "s0", "s1", "a0", "a1", "a2", "a3", "a4", "a5",
  "a6", "a7", "s2", "s3", "s4", "s5", "s6", "s7",
  "s8", "s9", "s10", "s11", "t3", "t4", "t5", "t6"
};

void isa_reg_display() {
  printf("No. Name  Value\n");
  for (size_t i = 0; i < MUXDEF(CONFIG_RVE, 16, 32); ++i) {
    printf("x%2lu %4s  " FMT_WORD ":%d\n", 
           i, reg_name(i), gpr(i), gpr(i));
  }
}

word_t isa_reg_str2val(const char *s, bool *success) {
  ssize_t len = strlen(s);
  *success = false;
  if (!s || len < 2 || len > 4) { return 0; }
  if (s[0] == 'x') {
    int id = -1;
    int n_read = sscanf(s+1, "%d", &id);
    if (n_read == 1 
        && id >= 0 && id < MUXDEF(CONFIG_RVE, 16, 32)) {
      *success = true;
      return gpr(id);
    } else {
      return 0;
    }
  } else {
    size_t i;
    for (i = 0; i < MUXDEF(CONFIG_RVE, 16, 32); ++i) {
      if (strcmp(regs[i], s) == 0) { 
        *success = true;
        break; 
      }
    }
    if (*success) { return gpr(i); }
    else { return 0; }
  }
  return 0;
}
