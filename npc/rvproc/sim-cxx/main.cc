#include <ctime>
#include <memory>
#include <cstdlib>

#include <numeric>
#include <verilated.h>
#include <verilated_fst_c.h>

#include "VrvCore.h"

inline void 
single_cycle(
    const std::unique_ptr<TOP_NAME>& top, 
    const std::unique_ptr<VerilatedContext>& context) {

  context->timeInc(1);
  top->clock = 1;
  top->eval();
  top->clock = 0;
  top->eval();
}

inline void 
single_reset(
    const std::unique_ptr<TOP_NAME>& top, 
    const std::unique_ptr<VerilatedContext>& context) {

  context->timeInc(1);
  top->reset = 1;
  for (size_t i = 0; i < 5; i++) {
    single_cycle(top, context);
  }
  top->reset = 0;
  single_cycle(top, context);
}

int 
main(int argc, char* argv[]) {
  const std::unique_ptr<VerilatedContext> contextp { new VerilatedContext };

  Verilated::traceEverOn(true);
  // VerilatedVcdC* tfp = new VerilatedVcdC;
  VerilatedFstC* tfp = new VerilatedFstC;

  const std::unique_ptr<TOP_NAME> top{new TOP_NAME{contextp.get(), "TOP"}};
  // Trace 99 levels of hierarchy (or see below)
  top->trace(tfp, 99);
  // tfp->dumpvars(1, "t"); // trace 1 level under "t"
  tfp->open("/home/kong/ysyx-workbench/npc/build-sim/rvproc/logs/simcc.log");

  single_reset(top, contextp);

  constexpr size_t MaxCyc{ 30U };
  size_t currCyc{ 1U };
  while (currCyc < MaxCyc) {
    single_cycle(top, contextp);

    currCyc++;
  }
  top->final();
  tfp->close();
  return 0;
}
