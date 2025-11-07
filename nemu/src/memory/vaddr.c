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
#include <debug.h>
#include <memory/paddr.h>

word_t vaddr_ifetch(vaddr_t addr, int len) {
  return paddr_read(addr, len);
}

word_t vaddr_read(vaddr_t addr, int len) {
  Assert(len == 1 || len == 2 || len == 4 || len == sizeof(word_t), 
         "Length %d not supported", len);
  Assert((addr & (vaddr_t)(len-1)) == 0, 
         "Read addr " FMT_WORD " not aligned to len %d", addr, len);
  return paddr_read(addr, len);
}

void vaddr_write(vaddr_t addr, int len, word_t data) {
  Assert(len == 1 || len == 2 || len == 4 || len == sizeof(word_t), 
         "Length %d not supported", len);
  Assert((addr & (vaddr_t)(len-1)) == 0, 
         "Write addr " FMT_WORD " not aligned to len %d", addr, len);
  paddr_write(addr, len, data);
}
