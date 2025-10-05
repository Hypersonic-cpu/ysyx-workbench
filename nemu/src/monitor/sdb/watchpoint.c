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

#include "sdb.h"
#include <string.h>

#define NR_WP 32

typedef struct watchpoint {
  int NO;
  struct watchpoint *next;

  char exprs[WP_STRMAX];

  word_t last_val;

} WP;

static WP wp_pool[NR_WP] = {};
static WP *head = NULL, *free_ = NULL;

void init_wp_pool() {
  int i;
  for (i = 0; i < NR_WP; i ++) {
    wp_pool[i].NO = i;
    wp_pool[i].next = (i == NR_WP - 1 ? NULL : &wp_pool[i + 1]);
  }

  head = NULL;
  free_ = wp_pool;
}

/* TODO: Implement the functionality of watchpoint */

int
new_wp(const char* const args, bool* valid) {
  if (free_ == NULL) {
    assert(0 && "No available space for new watchpoint");
  } 

  WP* sel = free_;
  free_ = sel->next;
  sel->next = head;
  head = sel;

  strncpy(sel->exprs, args, WP_STRMAX);
  sel->exprs[WP_STRMAX-1] = '\0';

  sel->last_val = expr(sel->exprs, valid);

  return sel->NO;
}

bool 
free_wp(int id) { 
  WP* cur = head;
  WP* last = NULL;
  while (cur) {
    if (cur->NO == id) {
      if (last == NULL) { 
        // Remove the 1st elem
        head = cur->next;
      } else {
        last->next = cur->next;
      }
      cur->next = free_;
      free_ = cur;
      return true;
    }
    last = cur;
    cur = cur->next;
  }
  return false; 
}

void 
list_wp() {
  printf("Num\tLast Val  \tWhat\n");
  size_t cnt = 0;
  for (WP* cur = head; cur; ++cnt, cur = cur->next) {
    printf("%-3d\t0x%08x\t%s\n", cur->NO, cur->last_val, cur->exprs);
  }
  printf("%lu active in total\n", cnt);
}

bool 
trig_wp() {
  bool triggered = false;
  for (WP* cur = head; cur; cur = cur->next) {
    bool success;
    word_t val = expr(cur->exprs, &success);
    assert(success && "Watchpoint should eval successfully");

    if (val == cur->last_val) { continue; }

    if (!triggered) {
      printf("Watchpoints triggered\n");
      printf("Num\tLast Val  \tCurr Val  \tWhat\n");
    }
    printf("%-3d\t0x%08x\t0x%08x\t%s\n", cur->NO, 
           cur->last_val, val, cur->exprs);
    cur->last_val = val;
    triggered = true;
  }
  return triggered;
}

