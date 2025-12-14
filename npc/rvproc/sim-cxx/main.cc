#include <cassert>
#include <format>
#include <iostream>
#include <memory>
#include <verilated.h>
#include <verilated_fst_c.h>

#include "VysyxSoCFull.h"

#include "options.hh"
#include "runtime.hh"
#include "wave.hh"

template <bool E>
inline void
single_cycle(const std::unique_ptr<TOP_NAME>& top,
             const std::unique_ptr<VerilatedContext>& context,
             const trace::FstTracer<E>& wave) {

  top->clock = 1;
  context->timeInc(1);
  top->eval();
  wave.dump(context->time());

  top->clock = 0;
  context->timeInc(1);
  top->eval();
  wave.dump(context->time());
}

template <bool E>
inline void
single_reset(const std::unique_ptr<TOP_NAME>& top,
             const std::unique_ptr<VerilatedContext>& context,
             const trace::FstTracer<E>& wave) {

  top->reset = 1;
  for (size_t i = 0; i < 15; i++) {
    single_cycle(top, context, wave);
  }
  top->clock = 1;
  context->timeInc(1);
  top->eval();
  wave.dump(context->time());

  top->clock = 0;
  top->reset = 0;
  context->timeInc(1);
  top->eval();
  wave.dump(context->time());
}

int
main(int argc, char* argv[]) {
  Verilated::commandArgs(argc, argv);
  assert(argc >= 2);

  auto mromBin = std::make_shared<RuntimeBin>(argv[1], 0x2000'0000U);
  mrom = mromBin.get();

  options::parse_args(argc, argv);

  const std::unique_ptr<VerilatedContext> contextp{new VerilatedContext};

  const std::unique_ptr<TOP_NAME> top{new TOP_NAME{contextp.get(), "TOP"}};
  trace::FstTracer<options::wave_enable> tfp(options::wave_file);
  if constexpr (options::wave_enable) {
    Verilated::traceEverOn(true);
    top->trace(tfp.get(), 99);
    tfp.open();
  }

  single_reset(top, contextp, tfp);

  constexpr size_t MaxCyc = 1000'000U;
  size_t currCyc{0U};

  while (!contextp->gotFinish() && currCyc < MaxCyc) {
    if (options::runtime_dump_opt.cycle_no)
      std::cerr << std::format("\r== @posedge of Cycle #{} ==", currCyc)
                << std::endl;

    currCyc++;
    single_cycle(top, contextp, tfp);

  }
  top->final();
  return currCyc == MaxCyc;
}
