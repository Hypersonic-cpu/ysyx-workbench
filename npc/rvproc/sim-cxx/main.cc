#include <format>
#include <iostream>
#include <verilated.h>
#include <verilated_fst_c.h>

#include "VysyxSoCFull.h"

extern "C" void
flash_read(int32_t addr, int32_t *data) {
  assert(0);
}
extern "C" void
mrom_read(int32_t addr, int32_t *data) {
  *data = (0x00100073U); // ebreak
  std::cerr << std::format("MROM read @ {:8x} = {:8x}", addr, *data)
            << std::endl;
  // assert(0);
}

inline void
single_cycle(const std::unique_ptr<TOP_NAME> &top,
             const std::unique_ptr<VerilatedContext> &context,
             const std::unique_ptr<VerilatedFstC> &fstwave) {

  top->clock = 1;
  context->timeInc(1);
  top->eval();
  fstwave->dump(context->time());

  top->clock = 0;
  context->timeInc(1);
  top->eval();
  fstwave->dump(context->time());
}

inline void
single_reset(const std::unique_ptr<TOP_NAME> &top,
             const std::unique_ptr<VerilatedContext> &context,
             const std::unique_ptr<VerilatedFstC> &fstwave) {

  top->reset = 1;
  for (size_t i = 0; i < 5; i++) {
    single_cycle(top, context, fstwave);
  }
  top->clock = 1;
  context->timeInc(1);
  top->eval();
  fstwave->dump(context->time());

  top->clock = 0;
  top->reset = 0; // cancel reset @ falling edge
  context->timeInc(1);
  top->eval();
  fstwave->dump(context->time());
}

int
main(int argc, char *argv[]) {
  Verilated::commandArgs(argc, argv);

  const std::unique_ptr<VerilatedContext> contextp{new VerilatedContext};

  Verilated::traceEverOn(true);
  const std::unique_ptr<VerilatedFstC> tfp{new VerilatedFstC};

  const std::unique_ptr<TOP_NAME> top{new TOP_NAME{contextp.get(), "TOP"}};

  top->trace(tfp.get(), 99);
  // tfp->dumpvars(1, "t"); // trace 1 level under "t"
  tfp->open("logs/soc.fst");

  single_reset(top, contextp, tfp);

  constexpr size_t MaxCyc = 15U;
  size_t currCyc{0U};

  while (!contextp->gotFinish() && currCyc < MaxCyc) {

    std::cerr << std::format("== @posedge of Cycle #{} ==", currCyc)
              << std::endl;
    currCyc++;
    single_cycle(top, contextp, tfp);
  }
  top->final();
  tfp->close();
}
