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
#include <cpu/cpu.h>
#include <readline/readline.h>
#include <readline/history.h>
#include "sdb.h"
#include "common.h"
#include "debug.h"
#include "memory/vaddr.h"
#include "utils.h"

static int is_batch_mode = false;

void init_regex();
void init_wp_pool();

/* We use the `readline' library to provide more flexibility to read from stdin. */
static char* rl_gets() {
  static char *line_read = NULL;

  if (line_read) {
    free(line_read);
    line_read = NULL;
  }

  line_read = readline("(nemu) ");

  if (line_read && *line_read) {
    add_history(line_read);
  }

  return line_read;
}

static int cmd_si(char *args) {
  uint64_t cmd_to_go = 0;
  // NULL will cause seg fault, while 
  // other errors will let `cmd_to_go=0` which causes no harm.
  if (args) { cmd_to_go = atoll(args); }
  // Set the default value to 1;
  cmd_to_go += (cmd_to_go == 0);
  cpu_exec(cmd_to_go);
  return 0;
}

static int cmd_info(char *args) {
  char* arg = strtok(NULL, " ");
  if (arg == NULL) {
    printf("Invalid arguments, type `help info` for more info\n");
    return 1;
  }
  switch (arg[0]) {
    case 'r':
      isa_reg_display();
      return 0;
    case 'w':
      // TODO: Print watchpoints
      TODO();
      return 0;
    default: 
      printf("Invalid argument `%c`, type `help info` for more info\n", arg[0]);
      return 1;
  }
}

static int cmd_c(char *args) {
  cpu_exec(-1);
  return 0;
}


static int cmd_q(char *args) {
  nemu_state.state = NEMU_QUIT;
  return -1;
}

static int cmd_x(char *args) {
  char *arg = strtok(NULL, " ");

  if (arg == NULL) {
    printf("Invalid arguments, type `help x` for more info\n");
    return 1;
  }
  size_t scan_num = atoll(arg);
  // printf("Scan len : %lu Bytes\n", scan_num * sizeof(word_t));
  
  arg = strtok(NULL, " ");
  if (arg == NULL) {
    printf("Invalid arguments, type `help x` for more info\n");
    return 1;
  }
  vaddr_t base_addr = strtoull(arg, NULL, 16);
  // printf("Scan base : %#x\n", base_addr);

  for (size_t idx = 0; idx < scan_num; ++idx) {
    vaddr_t cur = base_addr + idx * sizeof(word_t);
    if (idx % 4 == 0) {
      printf("%#x:", cur);
    }
    printf(" \t" FMT_WORD, vaddr_read(cur, sizeof(word_t)));
    if (idx % 4 == 3 || idx+1 == scan_num) {
      printf("\n");
    }
  }
  return 0;
}

static int cmd_p(char *args) {
  bool success = false;
  word_t val = expr(args, &success);
  if (success) {
    printf("%u\n", val);
    return 0;
  } else {
    printf("Expression eval failed\n");
    return 1;
  }
}

static int cmd_w(char *args) {
  int id = watchpoint_set(args);
  printf("Watchpoint %d set: %s\n", id, args);
  return 0;
}

static int cmd_d(char *args) {
  int id = atoi(args);
  bool success = watchpoint_del(id);
  printf("Watchpoint %d removal %s\n", 
         id, success ? "success" : "failed");
  return 0;
}

static int cmd_help(char *args);

static struct {
  const char *name;
  const char *description;
  int (*handler) (char *);
} cmd_table [] = {
  { "help", "Display information about all supported commands", cmd_help },
  { "c", "Continue the execution of the program", cmd_c },
  { "q", "Exit NEMU", cmd_q },
  { "si", "Arg [$N=1], execute `$N` steps", cmd_si },
  { "info", "Arg <r|w>, show info of registers|watchpoints", cmd_info }, 
  { "x", "Arg <$nw> <$VA(hex)> scan next $nw words from mem $VA", cmd_x }, 
  { "p", "Arg <$expr> evaluate expression", cmd_p }, 
  { "w", "Arg <$expr> watchpoint, pause when $expr changes", cmd_w },
  { "d", "Arg <$N> delete watchpoint $N", cmd_d }
};

#define NR_CMD ARRLEN(cmd_table)

static int cmd_help(char *args) {
  /* extract the first argument */
  char *arg = strtok(NULL, " ");
  int i;

  if (arg == NULL) {
    /* no argument given */
    for (i = 0; i < NR_CMD; i ++) {
      printf("%s - %s\n", cmd_table[i].name, cmd_table[i].description);
    }
  }
  else {
    for (i = 0; i < NR_CMD; i ++) {
      if (strcmp(arg, cmd_table[i].name) == 0) {
        printf("%s - %s\n", cmd_table[i].name, cmd_table[i].description);
        return 0;
      }
    }
    printf("Unknown command '%s'\n", arg);
  }
  return 0;
}

void sdb_set_batch_mode() {
  is_batch_mode = true;
}

void sdb_mainloop() {
  if (is_batch_mode) {
    cmd_c(NULL);
    return;
  }

  for (char *str; (str = rl_gets()) != NULL; ) {
    char *str_end = str + strlen(str);

    /* extract the first token as the command */
    char *cmd = strtok(str, " ");
    if (cmd == NULL) { continue; }

    /* treat the remaining string as the arguments,
     * which may need further parsing
     */
    char *args = cmd + strlen(cmd) + 1;
    if (args >= str_end) {
      args = NULL;
    }

#ifdef CONFIG_DEVICE
    extern void sdl_clear_event_queue();
    sdl_clear_event_queue();
#endif

    int i;
    for (i = 0; i < NR_CMD; i ++) {
      if (strcmp(cmd, cmd_table[i].name) == 0) {
        if (cmd_table[i].handler(args) < 0) { return; }
        break;
      }
    }

    if (i == NR_CMD) { printf("Unknown command '%s'\n", cmd); }
  }
}

void init_sdb() {
  /* Compile the regular expressions. */
  init_regex();

  /* Initialize the watchpoint pool. */
  init_wp_pool();
}
