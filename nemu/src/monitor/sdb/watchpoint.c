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
watchpoint_set(char *expr) { 
  if (free_ == NULL) {
    assert(0 && "No available space for new watchpoint");
  } 

  WP* sel = free_;
  free_ = sel->next;

  strncpy(sel->exprs, expr, WP_STRMAX);
  sel->exprs[WP_STRMAX-1] = '\0';

  sel->next = head;
  head = sel;
  return sel->NO; 
}

bool 
watchpoint_del(int id) { 
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
watchpoint_list() {
  printf("Active watchpoints\n");
  size_t cnt = 0;
  for (WP* cur = head; cur; ++cnt, cur = cur->next) {
    printf("NO %2d : %s\n", cur->NO, cur->exprs);
  }
  printf("%lu active in total\n", cnt);
}

