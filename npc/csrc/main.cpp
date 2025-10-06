
#include <cstdio>
#include <cassert>

#include <memory>
#include <verilated.h>
#include <verilated_fst_c.h>

#include "Vexample.h"

int 
main(int argc, char* argv[]) {
  printf("[ CXX Wrapper for NPC ]\n");
  
  const std::unique_ptr<VerilatedContext> contextp { new VerilatedContext };
  Verilated::traceEverOn(true);
  Verilated::mkdir("logs");

  VerilatedFstC* tfp = new VerilatedFstC;

  const std::unique_ptr<Vexample> top { new Vexample{ contextp.get() } };
  top->trace(tfp, 99);
  tfp->open("logs/example.fst");

  while (contextp->time() < 100U && !contextp->gotFinish()) {
    contextp->timeInc(1);
    int a = rand() & 1;
    int b = rand() & 1;
    top->a = a;
    top->b = b;
    top->eval();
    printf("a = %d, b = %d, f = %d\n", a, b, top->f);
    tfp->dump(contextp->time());
  }

  top->final();
  tfp->close();
  delete tfp;
  return 0;
}
