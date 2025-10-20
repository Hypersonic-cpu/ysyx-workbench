#include <nvboard.h>
#include <chrono>
#include <thread>
#include "VPs2DetectorFpga.h"

#include <verilated.h>
#include <verilated_fst_c.h>

static TOP_NAME dut;

void nvboard_bind_all_pins(TOP_NAME* top);

static void single_cycle() {
  dut.clock = 0; dut.eval();
  dut.clock = 1; dut.eval();
}

static void reset(int n) {
  dut.reset = 1;
  while (n -- > 0) single_cycle();
  dut.reset = 0;
}

// constexpr auto SleepTime = std::chrono::milliseconds(50);

int main() {
  const std::unique_ptr<VerilatedContext> contextp { new VerilatedContext };
  Verilated::traceEverOn(true);
  VerilatedFstC* tfp = new VerilatedFstC;
  dut.trace(tfp, 99);
  tfp->open("/mnt/hgfs/Arch-PA/ysyx-workbench/npc/build-sim/njuprojn7/log.fst");

  nvboard_bind_all_pins(&dut);

  nvboard_init();

  reset(10);

  while(1) {
    nvboard_update();
    contextp->timeInc(1);
    single_cycle();
    tfp->dump(contextp->time());
  }
}
