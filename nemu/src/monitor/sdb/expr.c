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
#include "memory/vaddr.h"
#include "sdb.h"
#include <isa.h>

/* We use the POSIX regex functions to process regular expressions.
 * Type 'man regex' for more information about POSIX regex functions.
 */
#include <regex.h>
#include <stdio.h>
#include <string.h>

enum {
  TK_NOTYPE = 256, 
  /** Left-to-right */
  TK_EQ, // ==
  TK_NEQ, // != 
  TK_GT,
  TK_LT,
  TK_GEQ,
  TK_LEQ,
  TK_SLL,
  TK_SRL, // logical, for uint
  TK_UPOS,
  TK_UNEG,
  TK_LAND, // &&
  TK_LOR,
  // TK_LNOT,
  TK_BAND,
  TK_BXOR,
  TK_BOR,
  // TK_BNOT,
  TK_BRA, // (
  TK_KET, // )
  TK_DEREF, // *pointer
  TK_NUM,
  TK_REG,
};

static struct rule {
  const char *regex;
  int token_type;
} rules[] = {

  {" +", TK_NOTYPE},    // spaces
  {"\\+", '+'},         // plus
  {"-", '-'},           // minus
  {"\\*", '*'},         // mul
  {"\\/", '/'},         // div
  {"\\(", TK_BRA },
  {"\\)", TK_KET },
  // match before '&'
  {"&&", TK_LAND}, 
  {"||", TK_LOR }, 
  // Hex, must come before decimal to prevent match 
  // of '0' in '0xff'
  {"0[xX][0-9A-Fa-f]+", TK_NUM }, 
  {"[0-9]+", TK_NUM }, 
  {"\\$[A-Za-z0-9]+", TK_REG },
  // match before > and <
  {">>", TK_SRL},
  {"<<", TK_SLL},
  {">=", TK_GEQ},
  {"<=", TK_LEQ},
  {"==", TK_EQ },        // equal
  {"!=", TK_NEQ},
  {">", TK_GT},
  {"<", TK_LT},
  {"&", TK_BAND},
  {"\\^", TK_BXOR}, 
  {"|", TK_BOR },
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
  char str[TOKEN_STRMAX];
} Token;

static Token tokens[TOKEN_ARRSIZE] __attribute__((used)) = {};
static int nr_token __attribute__((used))  = 0;

static inline bool 
is_unary(Token* tk) {
  switch (tk->type) {
    case TK_UPOS: case TK_UNEG: case TK_DEREF: return true;
    default: return false;
  }
}

static bool make_token(char *e) {
  int position = 0;
  int i;
  regmatch_t pmatch;

  nr_token = 0;

  // printf(" >>>> %lu\n", strlen(e));
  while (e[position] != '\0') {
    // printf(" >>>> %d\n", position);
    /* Try all rules one by one. */
    for (i = 0; i < NR_REGEX; i ++) {
      if (regexec(&re[i], e + position, 1, &pmatch, 0) == 0 && pmatch.rm_so == 0) {
        char *substr_start = e + position;
        int substr_len = pmatch.rm_eo;

        // printf( ANSI_FG_BLUE "match rules[%d] = \"%s\" at position %d with len %d: %.*s\n" ANSI_NONE,
        //     i, rules[i].regex, position, substr_len, substr_len, substr_start);
        // Log("match rules[%d] = \"%s\" at position %d with len %d: %.*s",
        //     i, rules[i].regex, position, substr_len, substr_len, substr_start);

        // printf("position %d += %d\n", position, substr_len);
        position += substr_len;

        // printf("cur nr_token = %d\n", nr_token);
        switch (rules[i].token_type) {
          case TK_NOTYPE: 
            break;
          case TK_NUM:
            if (substr_len >= TOKEN_STRMAX) {
              printf("buffer overflow at position %d\n%s\n%*.s^\n", 
                     position, e, position, "");
              return false;
            }
            tokens[nr_token].type = rules[i].token_type;
            strncpy(tokens[nr_token].str, substr_start, substr_len);
            tokens[nr_token].str[substr_len] = '\0';
            nr_token++;
            break;
          case TK_REG:
            if (substr_len >= TOKEN_STRMAX) {
              printf("buffer overflow at position %d\n%s\n%*.s^\n", 
                     position, e, position, "");
              return false;
            }
            assert(substr_len > 0);
            tokens[nr_token].type = rules[i].token_type;
            // Skip '\$' character.
            strncpy(tokens[nr_token].str, substr_start+1, substr_len-1);
            tokens[nr_token].str[substr_len-1] = '\0';
            nr_token++;
            break;
          default:
            tokens[nr_token].type = rules[i].token_type;
            nr_token++;
            break;
        }
        break;
      }
    }

    if (i == NR_REGEX) {
      printf("no match at position %d\n%s\n%*.s^\n", 
             position, e, position, "");
      return false;
    }
  }

  for (size_t i = 0; i < nr_token; ++i) {
    bool unary = i == 0 || 
      !(tokens[i-1].type == TK_NUM || 
        tokens[i-1].type == TK_REG || 
        tokens[i-1].type == TK_KET);
    if (tokens[i].type == '-' && unary) {
      tokens[i].type = TK_UNEG;
    } else if (tokens[i].type == '+' && unary) {
      tokens[i].type = TK_UPOS;
    } else if (tokens[i].type == '*' && unary) {
      tokens[i].type = TK_DEREF;
    }
  }

  return true;
}

static bool check_braket(int l, int r, bool* valid) {
  // We do not modify `valid` since we didn't check that.
  // Premature exit is resonable because even if the 
  // whole expression is checked, we cannot guarantee every 
  // sub-expression is valid. So we leave it to further process.
  if (!(tokens[l].type == TK_BRA && tokens[r].type == TK_KET)) {
    return false; 
  }
  int par_lv = 1;
  // Does not exit here to check for valid
  for (size_t i = l+1; i <= r-1; ++i) {
    if (tokens[i].type == TK_BRA) { par_lv ++; }
    else if (tokens[i].type == TK_KET) { par_lv --; }
    if (par_lv < 0) { *valid = false; return false; }
    if (par_lv == 0) { return false; }
  }

  if (par_lv != 1) { *valid = false; return false; }
  return true;
}

static int choose_pivot(int l, int r, bool* valid) {
  // NOTE: Rules:
  // 0. Given that the whole expr is NOT surrounded by ().
  // 1. Cannot in braket.
  // 2. The the expr is flattened: <0> op <1> op <2> ...
  // 3. Choose the lowest-level or right-most op. (all 
  //    binary ops are left assoc so far).
  // X. precedence: 
  //    0: Highest, None
  //    3: Unary *deref, +pos, -neg
  //    5: '*/'
  //    6: '+-'
  //    7: '>>' '<<'
  //    9:  > < >= <=
  //    10: '==', '!=' 
  //    14: Logical &&

  int par_lv = 0;
  int8_t preced = 0;
  int ret = -1;
  for (int i = l; i <= r; ++i) {
    if (tokens[i].type == TK_NUM || 
        tokens[i].type == TK_REG) { /* skip */ }
    else if (tokens[i].type == TK_BRA) { par_lv ++; }
    else if (tokens[i].type == TK_KET) { par_lv --; }
    else if (par_lv) { /* skip */ }
    else {
      // operators
      int8_t cur_preced = 0;
      switch (tokens[i].type) {
        case TK_LOR : cur_preced = 15; break;
        case TK_LAND: cur_preced = 14; break;
        case TK_BOR : cur_preced = 13; break;
        case TK_BXOR: cur_preced = 12; break;
        case TK_BAND: cur_preced = 11; break;
        case TK_EQ: case TK_NEQ:
          cur_preced = 10;
          break;
        case TK_GT: case TK_LT: case TK_GEQ: case TK_LEQ:
          cur_preced = 9;
          break;
        case TK_SLL: case TK_SRL:
          cur_preced = 7; 
          break;
        case '+': case '-': 
          cur_preced = 6;
          break;
        case '*': case '/':
          cur_preced = 5;
          break;
        case TK_UPOS: case TK_UNEG: case TK_DEREF:
          cur_preced = 3;
          break;
        default:
          assert(false && "Unexpected operator type");
          return 0;
      }
      // Assuming left-assoc
      if (cur_preced >= preced) {
        preced = cur_preced;
        ret = i;
      }
    }
  }

  if (ret < 0) { *valid = false; }
  else if (is_unary(tokens+ret)) {
    // NOTE: Unary op is right-assoc
    assert(is_unary(tokens+l));
    ret = l;
  }
  return ret;
}

// NOTE: Initial *valid should be true.
static word_t eval(int l, int r, bool* valid) {
  // printf("! Eval (%d, %d) V%d\n", l, r, *valid);
  if (!(*valid)) { return 0; }
  if (l > r) {
    *valid = false;
    return 0;
  } 
  if (l == r) {
    if (tokens[l].type == TK_NUM) {
      const char* const snum = tokens[l].str;
      word_t val = 0;
      int num_matched = sscanf(snum, "%i", &val);
      if (num_matched != 1) { *valid = false; }
      return val;
    } else if (tokens[l].type == TK_REG) {
      const char* str = tokens[l].str;
      if (!strcmp(str, "pc") || !strcmp(str, "PC")) {
        // NOTE: implicit cast to word_t
        return cpu.pc;
      }
      word_t val = isa_reg_str2val(tokens[l].str, valid);
      return val;
    } else {
      *valid = false;
      return 0;
    }
  }
  bool bra_ket = check_braket(l, r, valid);
  // printf("Braket (%d, %d) V%d Ret%d\n", l, r, *valid, bra_ket);
  if (!*valid) { return 0; }
  if (bra_ket) { return eval(l+1, r-1, valid); }
  
  int pivot_pos = choose_pivot(l, r, valid);
  // printf("Pivot (%d, <%d>, %d) V%d\n", l, pivot_pos, r, *valid);
  if (!*valid) { return 0; }

  assert(pivot_pos >= l && pivot_pos <= r);

  bool lvalid = true, rvalid = true;
  word_t lret = 0, rret = 0;

  if (!is_unary(tokens+pivot_pos)) {
    lret = eval(l, pivot_pos-1, &lvalid);
    // printf("L ret %d V%d\n", lret, lvalid);
    if (!lvalid) { *valid = false; return 0; }
  }

  rret = eval(pivot_pos+1, r, &rvalid);
  // printf("R ret %d V%d\n", rret, rvalid);
  if (!rvalid) { *valid = false; return 0; }

  word_t res = 0;
  switch (tokens[pivot_pos].type) {
    case '+': res = (lret + rret); break;
    case '-': res = (lret - rret); break;
    case TK_UPOS: res = rret; break;
    case TK_UNEG: res = -rret; break;
    case '*': res = (lret * rret); break;
    case '/': res = (lret / rret); break;
    case TK_SLL: res = (lret << rret); break;
    case TK_SRL: res = (lret >> rret); break;
    case TK_EQ: res = (lret == rret); break;
    case TK_GT: res = (lret > rret); break;
    case TK_LT: res = (lret < rret); break;
    case TK_GEQ: res = (lret >= rret); break;
    case TK_LEQ: res = (lret <= rret); break;
    case TK_NEQ: res = (lret != rret); break;
    case TK_LAND: res = (lret && rret); break;
    case TK_LOR : res = (lret || rret); break;
    case TK_BAND: res = (lret & rret); break;
    case TK_BXOR: res = (lret ^ rret); break;
    case TK_BOR : res = (lret | rret); break;
    case TK_DEREF:
      res = vaddr_read(rret, sizeof(word_t));
      break;
    default:
      assert(false && "Unexpected operator");
      break;
  }

  // printf("> Join L(%d,%d) %u R(%d,%d) %u Res %u\n", 
         // l, pivot_pos-1, lret, pivot_pos+1, r, rret, res);
  return res;
}

word_t expr(char *e, bool *valid) {
  bool loc_succ = true;
  
  word_t val = 0;
  if (!make_token(e) || nr_token == 0) {
    loc_succ = false;
  } else {
    val = eval(0, nr_token-1, &loc_succ);
  }

  if (valid) { *valid = loc_succ; }
  return val;
}
