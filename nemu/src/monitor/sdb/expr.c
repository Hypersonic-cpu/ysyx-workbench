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

#include "common.h"
#include "debug.h"
#include <isa.h>

/* We use the POSIX regex functions to process regular expressions.
 * Type 'man regex' for more information about POSIX regex functions.
 */
#include <regex.h>

enum {
  TK_NOTYPE = 256, TK_EQ,
  TK_BRA, // "("
  TK_KET, // ")"
  TK_NUM,

  /* TODO: Add more token types */

};

static struct rule {
  const char *regex;
  int token_type;
} rules[] = {

  /* TODO: Add more rules.
   * Pay attention to the precedence level of different rules.
   */

  {" +", TK_NOTYPE},    // spaces
  {"\\+", '+'},         // plus
  {"-", '-'},           // minus | negation
  {"\\*", '*'},         // mul
  {"\\/", '/'},         // div
  {"\\(", TK_BRA },
  {"\\)", TK_KET },
  {"[0-9]+", TK_NUM }, 
  {"==", TK_EQ},        // equal
};

#define NR_REGEX ARRLEN(rules)

static regex_t re[NR_REGEX] = {};

/* Rules are used for many times.
 * Therefore we compile them only once before any usage.
 */
void init_regex() {
  int i;
  char error_msg[128];
  int ret;

  for (i = 0; i < NR_REGEX; i ++) {
    ret = regcomp(&re[i], rules[i].regex, REG_EXTENDED);
    if (ret != 0) {
      regerror(ret, &re[i], error_msg, 128);
      panic("regex compilation failed: %s\n%s", error_msg, rules[i].regex);
    }
  }
}

typedef struct token {
  int type;
  char str[32];
} Token;

static Token tokens[32] __attribute__((used)) = {};
static int nr_token __attribute__((used))  = 0;

static bool make_token(char *e) {
  int position = 0;
  int i;
  regmatch_t pmatch;

  nr_token = 0;

  while (e[position] != '\0') {
    /* Try all rules one by one. */
    for (i = 0; i < NR_REGEX; i ++) {
      if (regexec(&re[i], e + position, 1, &pmatch, 0) == 0 && pmatch.rm_so == 0) {
        char *substr_start = e + position;
        int substr_len = pmatch.rm_eo;

        Log("match rules[%d] = \"%s\" at position %d with len %d: %.*s",
            i, rules[i].regex, position, substr_len, substr_len, substr_start);

        position += substr_len;

        /* TODO: Now a new token is recognized with rules[i]. Add codes
         * to record the token in the array `tokens'. For certain types
         * of tokens, some extra actions should be performed.
         */

        switch (rules[i].token_type) {
          case TK_NOTYPE: 
            break;
          case '+': case '-': case '*': case '/':
          case TK_BRA: case TK_KET: case TK_EQ:
            tokens[nr_token].type = rules[i].token_type;
            nr_token++;
            break;
          case TK_NUM:
            if (substr_len >= 32) {
              printf("buffer overflow at position %d\n%s\n%*.s^\n", position, e, position, "");
              return false;
            }
            tokens[nr_token].type = rules[i].token_type;
            strncpy(tokens[nr_token].str, substr_start, substr_len);
            tokens[nr_token].str[substr_len] = '\0';
            nr_token++;
            break;
          default: 
            assert(false && "Unknown token type encountered");
        }

        break;
      }
    }

    if (i == NR_REGEX) {
      printf("no match at position %d\n%s\n%*.s^\n", position, e, position, "");
      return false;
    }
  }

  return true;
}

static bool check_braket(size_t l, size_t r, bool* valid) {
  int par_lv = 0;
  for (size_t i = l; i <= r; ++i) {
    if (tokens[i].type == TK_BRA) { par_lv ++; }
    else if (tokens[i].type == TK_KET) { par_lv --; }
    if (par_lv < 0) { *valid = false; return false; }
  }
  return par_lv == 0 && 
    tokens[l].type == TK_BRA && tokens[r].type == TK_KET;
}

static int choose_pivot(int l, int r, bool* valid) {
  // NOTE: Rules:
  // 0. Given that the whole expr is NOT surrounded by ().
  // 1. Cannot in braket.
  // 2. The the expr is flattened: <0> op <1> op <2> ...
  // 3. Choose the lowest-level or right-most op. (all 
  //    binary ops are left assoc so far).
  // X. precedence:
  //    1: '=='
  //    2: '+-'
  //    3: '*/'

  int par_lv = 0;
  int8_t preced = 0xf;
  int ret = 0;
  for (int i = l; i <= r; ++i) {
    if (tokens[i].type == TK_NUM) { /* skip */ }
    else if (tokens[i].type == TK_BRA) { par_lv ++; }
    else if (tokens[i].type == TK_KET) { par_lv --; }
    else if (par_lv) { /* skip */ }
    else {
      // operators
      int8_t cur_preced = 0;
      switch (tokens[i].type) {
        case TK_EQ: 
          cur_preced = 1;
          break;
        case '+': case '-': 
          cur_preced = 2;
          break;
        case '*': case '/':
          cur_preced = 3;
          break;
        default:
          assert(false && "Unexpected operator type");
          return 0;
      }
      if (cur_preced <= preced) {
        preced = cur_preced;
        ret = i;
      }
    }
  }
  return ret;
}

// NOTE: Initial *valid should be true.
static sword_t eval(int l, int r, bool* valid) {
  printf("Eval (%d, %d) V%d\n", l, r, *valid);
  if (!(*valid)) { return 0; }
  if (l > r) {
    *valid = false;
    return 0;
  } 
  if (l == r) {
    assert(tokens[l].type == TK_NUM);
    return atoi(tokens[l].str);
  } 
  bool bra_ket = check_braket(l, r, valid);
  printf("Braket (%d, %d) V%d Ret%d\n", l, r, *valid, bra_ket);
  if (!*valid) { return 0; }
  if (bra_ket) { return eval(l+1, r-1, valid); }
  
  int pivot_pos = choose_pivot(l, r, valid);
  if (!*valid) { return 0; }

  printf("Pivot (%d, <%d>, %d)\n", l, pivot_pos, r);
  assert(pivot_pos >= l && pivot_pos <= r);

  bool lvalid = true, rvalid = true;
  sword_t lret = eval(l, pivot_pos-1, &lvalid);
  printf("L ret %d V%d\n", lret, lvalid);

  sword_t rret = eval(pivot_pos+1, r, &rvalid);
  printf("R ret %d V%d\n", rret, rvalid);

  if (!lvalid || !rvalid) { 
    *valid = false;
    return 0; 
  }

  switch (tokens[pivot_pos].type) {
    case '+': 
      return (lret + rret);
    case '-': 
      return (lret - rret);
    case '*': 
      return (lret * rret);
    case '/': 
      // TODO: Div 0 exception
      return (lret / rret);
    case TK_EQ:
      return (lret == rret);
    default:
      assert(false && "Unexpected operator");
      return 0;
  }
}

word_t expr(char *e, bool *success) {
  if (!make_token(e)) {
    *success = false;
    return 0;
  }

  /* TODO: Insert codes to evaluate the expression. */
  // TODO();

  if (nr_token == 0) { *success = false; return 0; }
  *success = true;
  sword_t val = eval(0, nr_token-1, success);

  return val;
}
