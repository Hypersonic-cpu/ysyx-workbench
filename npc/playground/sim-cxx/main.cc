#include <ctime>
#include <memory>
#include <cstdlib>

#include <verilated.h>
#include <verilated_fst_c.h>

#include "VGCD.h"
// #include "verilatedos.h"

// vluint64_t glb_time { 0 };
// double sc_time_stamp() { return glb_time; }

void single_cycle(const std::unique_ptr<TOP_NAME>& top) {
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
  tfp->open("/mnt/hgfs/Arch-PA/ysyx-workbench/npc/build-sim/playground/logs/log.fst");
  
  int passed = 0;
  int failed = 0;

  constexpr vluint64_t TimeMax = 100U;
  srand(time(0));
  while (contextp->time() < TimeMax && !contextp->gotFinish()) {
    contextp->timeInc(1);
    int a = rand() % 16;
    int b = rand() & 16;
    top->io_value1 = a;
    top->io_value2 = b;
    top->io_loadingValues = 1;
    single_cycle(top);
    while (!top->io_outputValid && contextp->time() < TimeMax) {
      contextp->timeInc(1);
      single_cycle(top);
    }
    printf("a = %d, b = %d, GCD = %d\n", a, b, top->io_outputGCD);
    if (contextp->time() < TimeMax) {
      printf("a = %d, b = %d, GCD = %d\n", a, b, top->io_outputGCD);
      passed++;
    } else {
      failed++;
    }
    tfp->dump(contextp->time());
    if (passed + failed) { break; }
  }

  top->final();
  tfp->close();
  return 0;
}
