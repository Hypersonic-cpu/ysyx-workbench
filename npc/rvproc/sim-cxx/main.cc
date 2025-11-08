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

int 
main() {
  const std::unique_ptr<VerilatedContext> contextp { new VerilatedContext };

  Verilated::traceEverOn(true);
  // VerilatedVcdC* tfp = new VerilatedVcdC;
  VerilatedFstC* tfp = new VerilatedFstC;

  const std::unique_ptr<TOP_NAME> top{new TOP_NAME{contextp.get(), "TOP"}};
  // Trace 99 levels of hierarchy (or see below)
  top->trace(tfp, 99);
  // tfp->dumpvars(1, "t"); // trace 1 level under "t"
  //
  tfp->open("/home/kong/ysyx-workbench/npc/build-sim/rvproc/logs/jalr.log");
  
  // int passed = 0;
  // int failed = 0;
  //
  // constexpr vluint64_t TimeMax = 100U;
  // srand(time(0));
  // while (contextp->time() < TimeMax && !contextp->gotFinish()) {
  //   int a = rand() % 15 + 1;
  //   int b = rand() % 15 + 1;
  //   top->io_value1 = a;
  //   top->io_value2 = b;
  //   top->io_loadingValues = 1;
  //   single_cycle(top, contextp);
  //   single_cycle(top, contextp);
  //   top->io_loadingValues = 0;
  //   while (!top->io_outputValid && contextp->time() < TimeMax) {
  //     printf("%lu: a = %d, b = %d, GCD = %d\n", contextp->time(), a, b, top->io_outputGCD);
  //     single_cycle(top, contextp);
  //     tfp->dump(contextp->time());
  //   }
  //   if (contextp->time() < TimeMax) {
  //     printf("a = %d, b = %d, GCD = %d\n", a, b, top->io_outputGCD);
  //     if (top->io_outputGCD == std::gcd(a, b)) { passed ++; }
  //     else { failed ++; }
  //   } else {
  //     printf("a = %d, b = %d, Timeout", a, b);
  //     failed ++;
  //   }
  //   tfp->dump(contextp->time());
  //   if (passed + failed) { break; }
  // }
  //

  constexpr size_t MaxCyc{ 10U };
  size_t currCyc{ 0U };
  while (currCyc < MaxCyc) {
    single_cycle(top, contextp);

    currCyc++;
  }
  top->final();
  tfp->close();
  return 0;
}
