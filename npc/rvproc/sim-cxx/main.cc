#include <ctime>
#include <iomanip>
#include <memory>
#include <cstdlib>
#include <iostream>

#include <numeric>
#include <verilated.h>
#include <verilated_fst_c.h>

#include "VrvCore.h"
#include "ccdb.hh"

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

// uint32_t 
// probe_reg(
//     const std::unique_ptr<TOP_NAME>& top, 
//     uint8_t regid) {
//   ccdb::set_reg_probe_idx(regid);
//
// }

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
  while (!contextp->gotFinish()) {
    single_cycle(top, contextp);
    currCyc++;
  }
  for (uint16_t i = 0; i < 16; ++i) {
    auto [v, res] = ccdb::read_reg(top, i);
    std::cerr << "Reg [" << std::dec << std::setw(2)<< i <<
      "] : 0x" << std::hex << std::setw(8) << res << std::endl;
  }
  {
    auto [v, res] = ccdb::read_reg(top, 0xff);
    std::cerr << "Reg [PC] : 0x" << std::hex << std::setw(8) << res << std::endl;
  }
  {
    for (uint32_t i = 0; i < 16; i += 4) {
      auto [v, res] = ccdb::read_mem(top, 0x8000'0000U + i);
      std::cerr << std::hex << std::setfill('0') << std::setw(8) << res << " ";
    }
    std::cerr << std::endl;
  }
  top->final();
  tfp->close();
  return 0;
}
