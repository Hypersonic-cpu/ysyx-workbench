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
#include <string.h>
#include <sys/types.h>

void init_monitor(int, char *[]);
void am_init_monitor();
void engine_start();
int is_exit_status_bad();

static bool 
__attribute__((unused))
expr_eval_test_unsigned(const char *const path) 
{
  FILE* fp = fopen(path, "r");
  if (!fp) { return false; }
  char* ln = NULL;
  int cnt = 0; 
  int errcnt = 0;
  bool success = true;
  size_t malloc_sz;
  ssize_t read_strlen;
  
  while ((read_strlen = getline(&ln, &malloc_sz, fp)) > 0) {
    // printf("Malloc %lu\n", malloc_sz);
    // printf("%s\n", ln);
    // printf("%lu\n", strlen(ln));
    ln[read_strlen-1] = 0;
    cnt ++;
    fprintf(stderr, "\rTesting case #%6d: ", cnt);
    word_t expected;
    int dig_len;
    int read_num = sscanf(ln, "%u%n", &expected, &dig_len);
    // printf("%u %d %d\n", expected, dig_len, read_num);
    assert(read_num == 1);
    char* exprstr = ln + dig_len;

    bool succ;
    word_t ret = expr(exprstr, &succ);

    if (!succ) {
      fprintf(stderr, "\n[RE:%6d] Expr parse error\n", cnt);
      fprintf(stderr, "Test Case:\n\"%s\"\n", exprstr);
      success = false;
      errcnt ++;
      // return false;
    } else if (expected != ret) {
      fprintf(stderr, "\n[WA:%6d] Expr parse error\n", cnt);
      fprintf(stderr, "Test Case:\n\"%s\"\n", exprstr);
      fprintf(stderr, "Expected: %u, Read %u\n", expected, ret);
      success = false;
      errcnt ++;
      // return false;
    } else {
      fprintf(stderr, "[AC:%6d] Pass", cnt);
    }
    free(ln);
    ln = NULL;
  }
  fclose(fp);
  fprintf(stderr, "\n=== Total %d Error %d ===\n", cnt, errcnt);
  return success;
}

static size_t const TEST_NUMS = 1;
static const char* const test_files[] = {
  "tools/gen-expr/input_all_arith_3251_nemu.txt", 
  "tools/gen-expr/input_nemu.txt",
  "tools/gen-expr/input_pos_neg_nemu.txt", 
  "tools/gen-expr/input_all_arith_nemu.txt", 
};

static bool 
do_expr_tests() {
  for (size_t i = 0; i < TEST_NUMS; ++i) {
    if (!expr_eval_test_unsigned(test_files[i])) {
      return false;
    }
  }
  return true;
}

int main(int argc, char *argv[]) {
  /* Initialize the monitor. */
#ifdef CONFIG_TARGET_AM
  am_init_monitor();
#else
  init_monitor(argc, argv);
#endif
   
  return !do_expr_tests();
  
  /* Start engine. */
  engine_start();

  return is_exit_status_bad();
}
