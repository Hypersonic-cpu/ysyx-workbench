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

#include "utils.h"
#include <isa.h>
#include <cpu/cpu.h>
#include <difftest-def.h>
#include <memory/paddr.h>

__EXPORT void difftest_memcpy(paddr_t addr, void *buf, size_t n, bool direction) {
  if (direction == DIFFTEST_TO_REF) { // =1
    for (size_t i = 0; i < n/4; i++) {
      size_t off = (i << 2);
      paddr_write(addr+off, 4, *(uint32_t *)(buf+off));
    }
    for (size_t i = 0; i < n%4; i++) {
      size_t off = (n & ~0b11) | i;
      paddr_write(addr+off, 1, *(uint8_t *)(buf+off));
    }
  } else {
    for (size_t i = 0; i < n/4; i++) {
      size_t off = (i << 2);
      uint32_t val = paddr_read(addr+off, 4);
      *(uint32_t *) (buf+off) = val;
    }
    for (size_t i = 0; i < n%4; i++) {
      size_t off = (n & ~0b11) | i;
      uint8_t val = paddr_read(addr+off, 1);
      *(uint8_t *) (buf+off) = val;
    }
  }
}

__EXPORT void difftest_regcpy(void *dut, bool direction) {
  if (direction == DIFFTEST_TO_REF) { // 1 
    size_t i = 0;
    for (i = 0; i < MUXDEF(CONFIG_RVE, 16, 32); i++) {
      cpu.gpr[i] = *((word_t *)dut + i);
    }
    cpu.pc = *((word_t *)dut + i);
  } else {
    size_t i = 0;
    for (i = 0; i < MUXDEF(CONFIG_RVE, 16, 32); i++) {
      *((word_t *)dut + i) = cpu.gpr[i] ;
    }
    *((word_t *)dut + i) = cpu.pc;
  }
}

__EXPORT void difftest_exec(uint64_t n) {
  nemu_state.state = NEMU_RUNNING;
  cpu_exec(n);
  nemu_state.state = NEMU_STOP;
}

__EXPORT void difftest_raise_intr(word_t NO) {
  assert(0);
}

__EXPORT void difftest_init(int port) {
  void init_mem();
  init_mem();
#if CONFIG_SOC
  void init_soc();
  init_soc();
#endif
  /* Perform ISA dependent initialization. */
  init_isa();
}

__EXPORT void difftest_get_memwr_event(void *dst) {
  isa_cpy_memwr_event(dst);
}
