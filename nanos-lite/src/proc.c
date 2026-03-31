#include "am.h"
#include <proc.h>

#define MAX_NR_PROC 4

static size_t nr_pcbs = 0;
static PCB pcb[MAX_NR_PROC] __attribute__((used)) = {};
static PCB pcb_boot = {};
PCB *current = NULL;
const char *init_prog = "/bin/nterm";

void naive_uload(PCB *pcb, const char *filename);

void context_kload(PCB *pcb, void (*fn)(void *), void *args) {
  pcb->cp = kcontext(
      (Area){.start = pcb->stack, .end = pcb->stack + STACK_SIZE}, fn, args);
}

void switch_boot_pcb() { current = &pcb_boot; }

void hello_fun(void *arg) {
  int j = 1;
  while (1) {
    Log("Hello World from Nanos-lite with arg '%p' for the %dth time!",
        (uintptr_t)arg, j);
    j++;
    yield();
  }
}

void init_proc() {
  context_kload(&pcb[nr_pcbs++], hello_fun, (void *)0x11);
  context_kload(&pcb[nr_pcbs++], hello_fun, (void *)0x22);
  switch_boot_pcb();

  Log("Initializing processes...");

  // naive_uload(NULL, init_prog);
}

Context *schedule(Context *prev) {
  assert(nr_pcbs > 0);
  static size_t idx_pcbs = 0;
  current->cp = prev;
  PCB *ret = &pcb[idx_pcbs];
  current = ret;
  Log("Scheduling PCB[%d]\n", idx_pcbs);
  idx_pcbs = (idx_pcbs + 1) % nr_pcbs;
  return ret->cp;
}
