#include "arch/riscv.h"
#include <am.h>
#include <stdint.h>
#include <riscv/riscv.h>
#include <klib.h>

static Context* (*user_handler)(Event, Context*) = NULL;

Context* __am_irq_handle(Context *c) {
  if (user_handler) {
    Event ev = {0};
    switch (c->mcause) {
      case 11:
        ev.event = EVENT_YIELD;
        c->mepc += 4;
        break;
      default: ev.event = EVENT_ERROR; break;
    }

    printf("DBG: AM Context switch from %p\n", (uintptr_t) c);
    c = user_handler(ev, c);
    printf("DBG AM Context switch to %p\n", (uintptr_t) c);
    assert(c != NULL);
  }

  return c;
}

extern void __am_asm_trap(void);

bool cte_init(Context*(*handler)(Event, Context*)) {
  // initialize exception entry
  asm volatile("csrw mtvec, %0" : : "r"(__am_asm_trap));

  // register event handler
  user_handler = handler;

  return true;
}

Context *kcontext(Area kstack, void (*entry)(void *), void *arg) {
  Context* ctx = kstack.end - sizeof(Context);
  for (size_t i = 0; i < sizeof(Context) / sizeof(uintptr_t); i++) {
    * ((uintptr_t *) ctx + i) = 0xBadC0DE;
  }
  ctx->gpr[10] = (uintptr_t) arg;
  ctx->gpr[ 2] = (uintptr_t) ctx;
  ctx->mepc = (uintptr_t) entry;
  ctx->mstatus = 0x1800;
  return ctx;
}

void yield() {
#ifdef __riscv_e
  asm volatile("li a5, -1; ecall");
#else
  asm volatile("li a7, -1; ecall");
#endif
}

bool ienabled() {
  return false;
}

void iset(bool enable) {
}
