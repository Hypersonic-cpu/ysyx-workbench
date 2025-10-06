
#include <cstdio>
#include <cassert>

#include <memory>
#include <verilated.h>
#include <verilated_fst_c.h>
#include <nvboard.h>

#include "Vexample.h"

static TOP_NAME dut;
void nvboard_bind_all_pins(TOP_NAME* top);

void 
single_cycle() {
  dut.clk = 1; dut.eval();
  dut.clk = 0; dut.eval();
}

void 
reset(int n) {
  dut.reset = 1;
  while (n-- > 0) {
    single_cycle();
  }
  dut.reset = 0;
}

int 
main(int argc, char* argv[]) {
  printf("[ CXX Wrapper for NPC ]\n");

  nvboard_bind_all_pins(&dut);
  nvboard_init();

  reset(10);
  while (true) {
    nvboard_update();
    single_cycle();
  }

  return 0;
}
