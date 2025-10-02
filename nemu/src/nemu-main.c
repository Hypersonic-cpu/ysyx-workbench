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

#include "monitor/sdb/sdb.h"
#include <assert.h>
#include <common.h>
#include <readline/readline.h>
#include <stdio.h>

void init_monitor(int, char *[]);
void am_init_monitor();
void engine_start();
int is_exit_status_bad();

bool expr_eval_test_unsigned(char *path) {
  FILE* fp = fopen(path, "r");
  char* ln = NULL;
  int cnt = 0; 
  int errcnt = 0;
  bool success = true;
  size_t malloc_sz;
  
  while (getline(&ln, &malloc_sz, fp) > 0) {
    printf("Malloc %lu\n", malloc_sz);
    // printf("%s\n", ln);
    // printf("%lu\n", strlen(ln));
    ln[strlen(ln)-1] = 0;
    cnt ++;
    fprintf(stderr, "Testing case #%6d: \n", cnt);
    word_t expected;
    int dig_len;
    int read_num = sscanf(ln, "%u%n", &expected, &dig_len);
    // printf("%u %d %d\n", expected, dig_len, read_num);
    assert(read_num == 1);
    char* exprstr = ln + dig_len;

    bool succ;
    word_t ret = expr(exprstr, &succ);

    if (!succ) {
      fprintf(stderr, "[RE:%6d] Expr parse error\n", cnt);
      fprintf(stderr, "Test Case:\n\"%s\"\n", exprstr);
      success = false;
      errcnt ++;
      return false;
    } else if (expected != ret) {
      fprintf(stderr, "[RE:%6d] Expr parse error\n", cnt);
      fprintf(stderr, "Test Case:\n\"%s\"\n", exprstr);
      fprintf(stderr, "Expected: %u, Read %u\n", expected, ret);
      success = false;
      errcnt ++;
      return false;
    } else {
      fprintf(stderr, "[AC:%6d] Pass\n", cnt);
    }
    free(ln);
    ln = NULL;
  }
  fclose(fp);
  fprintf(stderr, "Total %d Error %d\n", cnt, errcnt);
  return success;
}

int main(int argc, char *argv[]) {
  /* Initialize the monitor. */
#ifdef CONFIG_TARGET_AM
  am_init_monitor();
#else
  init_monitor(argc, argv);
#endif
   
  return expr_eval_test_unsigned("tools/gen-expr/input.txt");
  /* Start engine. */
  engine_start();

  return is_exit_status_bad();
}
