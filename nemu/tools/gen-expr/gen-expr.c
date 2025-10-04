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

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <assert.h>
#include <string.h>

static size_t buf_ptr = 0;
// this should be enough
static char buf[65536] = {};
static char code_buf[65536 + 128] = {}; // a little larger than `buf`
static char *code_format =
"#include <stdio.h>\n"
"int main() { "
"  unsigned result = %s; "
"  printf(\"%%u\", result); "
"  return 0; "
"}";

static const char* const ops[] = {
  "+", "-", "*", "/", 
  "+", "-", "*", "==" };

static void gen_rand_expr(int lim) {
  int wnum = 0;
  if (lim < 10) { 
    wnum = sprintf(buf+buf_ptr, "%s%1dU", 
                   rand() % 2 ? " -" : " ", rand()%10); 
    buf_ptr += wnum;
    return; 
  }
  switch (rand() % 5) {
    case 0: 
      wnum = sprintf(buf+buf_ptr, "%s%uU", 
                     rand()%2 ? " -" : " ", rand() % 10000); 
      buf_ptr += wnum;
      break;
    case 1: case 2: 
      sprintf(buf+buf_ptr, "("); buf_ptr++;
      gen_rand_expr(lim-1); 
      sprintf(buf+buf_ptr, ")"); buf_ptr++;
      break;
    default: 
      gen_rand_expr(lim/2-1); 
      wnum = sprintf(buf+buf_ptr, "%s", ops[rand() % 8]);
      buf_ptr += wnum;
      gen_rand_expr(lim/2-1); 
      break;
  }
}

int main(int argc, char *argv[]) {
  int seed = time(0);
  srand(seed);
  int loop = 1;
  if (argc > 1) {
    sscanf(argv[1], "%d", &loop);
  }
  int i;
  for (i = 0; i < loop; i ++) {
    fprintf(stderr, "\rGenerating testpoint #%6d", i);
    buf_ptr = 0;
    gen_rand_expr(65530);

    sprintf(code_buf, code_format, buf);

    FILE *fp = fopen("/tmp/.code.c", "w");
    assert(fp != NULL);
    fputs(code_buf, fp);
    fclose(fp);

    int ret = system("gcc /tmp/.code.c -Werror=div-by-zero -Wno-overflow"
                     " -o /tmp/.expr 2> /dev/null");
    if (ret != 0) continue;

    fp = popen("/tmp/.expr", "r");
    assert(fp != NULL);

    int result;
    ret = fscanf(fp, "%d", &result);
    pclose(fp);

    printf("%u %s\n", result, buf);
  }
  return 0;
}
