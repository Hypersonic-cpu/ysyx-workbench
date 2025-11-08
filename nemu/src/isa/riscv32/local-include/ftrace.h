#ifndef __RISCV_FTRACE_H__
#define __RISCV_FTRACE_H__

#include "isa-def.h"
#include "common.h"
#include "macro.h"
#include "debug.h"

#define SYM_TABLE_ENT 240
typedef struct {
  vaddr_t addr;
  char name[128];
} rv32_symbol;

__attribute_used__
typedef struct {
  unsigned sym_num;
  rv32_symbol table[SYM_TABLE_ENT];
} rv32_SymTable; 
extern rv32_SymTable symbols;

// Check if any symbol at addr. UINT_MAX if not.
unsigned symbol_which(vaddr_t addr);

#define FTRACE_STACK_SIZE 240
typedef struct {
  vaddr_t ra; // PC after func finish
  vaddr_t fn; // PC that matches the symbol table
  vaddr_t sp; // Stack pointer
  unsigned symt_idx; // sym table index
  word_t args[MUXDEF(CONFIG_RVE, 4, 8)];
} rv32_frame;

__attribute_used__
typedef struct {
  size_t num;
  rv32_frame stack[FTRACE_STACK_SIZE];
} rv32_FrStack;
extern rv32_FrStack frames;

#endif // !__RISCV_FTRACE_H__

