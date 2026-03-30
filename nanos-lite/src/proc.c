#include <proc.h>
#include <fs.h>

#define MAX_NR_PROC 4

static PCB pcb[MAX_NR_PROC] __attribute__((used)) = {};
static PCB pcb_boot = {};
PCB *current = NULL;
static const char *init_prog = "/bin/nterm";

void naive_uload(PCB *pcb, const char *filename);

void run_program(const char *filename) {
  fs_reset();
  naive_uload(NULL, filename);
}

void run_init_process(void) {
  run_program(init_prog);
}

void switch_boot_pcb() {
  current = &pcb_boot;
}

void hello_fun(void *arg) {
  int j = 1;
  while (1) {
    Log("Hello World from Nanos-lite with arg '%p' for the %dth time!", (uintptr_t)arg, j);
    j ++;
    yield();
  }
}

void init_proc() {
  switch_boot_pcb();

  Log("Initializing processes...");

  run_init_process();
}

Context* schedule(Context *prev) {
  return NULL;
}
