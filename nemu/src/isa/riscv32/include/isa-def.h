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

#ifndef __ISA_RISCV_H__
#define __ISA_RISCV_H__

#include <common.h>

#define RISCV_CSR_NUM 4096

#define RISCV_CSR_MSTATUS 0x300
#define RISCV_CSR_MTVEC   0x305
#define RISCV_CSR_MCAUSE  0x342
#define RISCV_CSR_MEPC    0x341
#define RISCV_CSR_MCYCLE  0xB00
#define RISCV_CSR_MVENDORID 0xF11
#define RISCV_CSR_MARCHID 0xF12
#define RISCV_CSR_SATP 0x180

word_t csr_read(int idx);

typedef struct {
  // WARN: Remind the order
  word_t gpr[MUXDEF(CONFIG_RVE, 16, 32)];
  vaddr_t pc;
  word_t csr[RISCV_CSR_NUM];
} MUXDEF(CONFIG_RV64, riscv64_CPU_state, riscv32_CPU_state);

// decode
typedef struct {
  uint32_t inst;
} MUXDEF(CONFIG_RV64, riscv64_ISADecodeInfo, riscv32_ISADecodeInfo);

#endif
