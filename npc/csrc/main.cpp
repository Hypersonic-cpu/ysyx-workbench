
#include <cstdio>
#include <cassert>

#include <memory>
#include <verilated.h>
#include <verilated_fst_c.h>
#include <nvboard.h>

#include "Vexample.h"

static TOP_NAME dut;
void nvboard_bind_all_pins(TOP_NAME* top);

int 
main(int argc, char* argv[]) {
  printf("[ CXX Wrapper for NPC ]\n");
  
  nvboard_bind_all_pins(&dut);
  nvboard_init();

  // const std::unique_ptr<VerilatedContext> contextp { new VerilatedContext };
  // Verilated::traceEverOn(true);
  // Verilated::mkdir("logs");
  //
  // VerilatedFstC* tfp = new VerilatedFstC;

  // const std::unique_ptr<Vexample> top { new Vexample{ contextp.get() } };
  // top->trace(tfp, 99);
  // tfp->open("logs/example.fst");

  // while (contextp->time() < 100U && !contextp->gotFinish()) {
  while (true) {
    nvboard_update();
    dut.eval();
    // contextp->timeInc(1);
    // int a = rand() & 1;
    // int b = rand() & 1;
    // top->a = a;
    // top->b = b;
    // top->eval();
    // printf("a = %d, b = %d, f = %d\n", a, b, top->f);
    // tfp->dump(contextp->time());
  }

  // top->final();
  // tfp->close();
  // delete tfp;
  return 0;
}
