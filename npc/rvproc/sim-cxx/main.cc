#include <cassert>
#include <format>
#include <iostream>
#include <memory>
#include <verilated.h>
#include <verilated_fst_c.h>

#include "VysyxSoCFull.h"

#include "probe.hh"
#include "runtime.hh"

inline void
single_cycle(const std::unique_ptr<TOP_NAME>& top,
             const std::unique_ptr<VerilatedContext>& context,
             const std::unique_ptr<VerilatedFstC>& fstwave) {

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
single_reset(const std::unique_ptr<TOP_NAME>& top,
             const std::unique_ptr<VerilatedContext>& context,
             const std::unique_ptr<VerilatedFstC>& fstwave) {

  top->reset = 1;
  for (size_t i = 0; i < 15; i++) {
    single_cycle(top, context, fstwave);
  }
  top->clock = 1;
  context->timeInc(1);
  top->eval();
  fstwave->dump(context->time());

  top->clock = 0;
  top->reset = 0;
  context->timeInc(1);
  top->eval();
  fstwave->dump(context->time());
}

int
main(int argc, char* argv[]) {
  Verilated::commandArgs(argc, argv);
  assert(argc >= 2);

  auto mromBin = std::make_shared<RuntimeBin>(argv[1], 0x2000'0000U);
  mrom = mromBin.get();

  const std::unique_ptr<VerilatedContext> contextp{new VerilatedContext};

  Verilated::traceEverOn(true);
  const std::unique_ptr<VerilatedFstC> tfp{new VerilatedFstC};

  const std::unique_ptr<TOP_NAME> top{new TOP_NAME{contextp.get(), "TOP"}};

  top->trace(tfp.get(), 99);
  // tfp->dumpvars(1, "t"); // trace 1 level under "t"
  tfp->open("logs/soc.fst");

  single_reset(top, contextp, tfp);

  constexpr size_t MaxCyc = 1000'000U;
  size_t currCyc{0U};

  while (!contextp->gotFinish() && currCyc < MaxCyc) {

    // std::cerr << std::format("\r== @posedge of Cycle #{} ==", currCyc);
              // << std::endl;
    currCyc++;
    single_cycle(top, contextp, tfp);
  }
  top->final();
  tfp->close();
  return currCyc == MaxCyc;
}

