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

#ifndef __RISCV_REG_H__
#define __RISCV_REG_H__

#include "debug.h"
#include <common.h>

#define RISCV_CSR_MSTATUS 0x300
#define RISCV_CSR_MTVEC   0x305
#define RISCV_CSR_MCAUSE  0x342
#define RISCV_CSR_MEPC    0x341
#define RISCV_CSR_MCYCLE  0xB00

static inline int check_reg_idx(int idx) {
  IFDEF(CONFIG_RT_CHECK, 
      Assert(idx >= 0 && idx < MUXDEF(CONFIG_RVE, 16, 32), 
        "Reg index %d out of bound", idx)
  );
  return idx;
}

static inline int check_csr_idx(int idx) {
  IFDEF(CONFIG_RT_CHECK, 
      Assert(
        idx == RISCV_CSR_MSTATUS ||
        idx == RISCV_CSR_MTVEC   ||
        idx == RISCV_CSR_MCAUSE  || 
        idx == RISCV_CSR_MEPC    ||
        idx == RISCV_CSR_MCYCLE,
        "Csr index 0x%x not implemented", idx)
  );
  return idx;
}

#define gpr(idx) (cpu.gpr[check_reg_idx(idx)])

static inline const char* reg_name(int idx) {
  extern const char* regs[];
  return regs[check_reg_idx(idx)];
}

#define csr(idx) (cpu.csr[check_csr_idx(idx)])

static inline const char* csr_name(int idx) {
  extern const char* csrs[];
  return csrs[check_csr_idx(idx)];
}

#endif
