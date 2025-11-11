#include <cassert>
#include <ctime>
#include <iomanip>
#include <memory>
#include <cstdlib>
#include <iostream>
#include <numeric>
#include <getopt.h>

#include <verilated.h>
#include <verilated_fst_c.h>

#include "VrvCore.h"
#include "probe.hh"
#include "ccdb.hh"
#include "disasm.hh"

void parse_args(int argc, char* argv[]) {
  constexpr struct option table[] = {
    {"print-mem"  , no_argument      , NULL, 'm'},
    {"print-dev"  , no_argument      , NULL, 'd'},
    {"print-frame", no_argument      , NULL, 'f'},
    {"log"        , required_argument, NULL, 'l'},
    {"elf"        , required_argument, NULL, 'e'},
    {"help"       , no_argument      , NULL, 'h'},
    {0            , 0                , NULL,  0 },
  };
  int o;
  while ( (o = getopt_long(argc, argv, "-hmdfl:e:", table, NULL)) != -1) {
    switch (o) {
      case 'm': comm::mtrace_print = true; break;
      case 'd': comm::dtrace_print = true; break;
      case 'f': comm::ftrace_print = true; break;
      case 'l': comm::log_wavefile = std::string(optarg); break;
      case 'e': comm::elf_file = optarg; break;
      default:
        // printf("Usage: %s [OPTION...] IMAGE [args]\n\n", argv[0]);
        printf("Invalid Arguments.\n\n");
        exit(1);
    }
  }
}

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
  parse_args(argc, argv);

  const std::unique_ptr<VerilatedContext> contextp { new VerilatedContext };

  Verilated::traceEverOn(true);
  // VerilatedVcdC* tfp = new VerilatedVcdC;
  VerilatedFstC* tfp = new VerilatedFstC;

  const std::unique_ptr<TOP_NAME> top{new TOP_NAME{contextp.get(), "TOP"}};
  if (!comm::log_wavefile.empty()) {
    // Trace 99 levels of hierarchy (or see below)
    top->trace(tfp, 99);
    // tfp->dumpvars(1, "t"); // trace 1 level under "t"
    tfp->open(comm::log_wavefile.c_str());
  }

  ccdb::trace_init();
  single_reset(top, contextp);

  constexpr size_t MaxCyc{ 30U };
  size_t currCyc{ 1U };
  while (!contextp->gotFinish()) {
    ccdb::inst_trace(top);
    single_cycle(top, contextp);
    currCyc++;
  }
  for (uint16_t i = 0; i < 16; ++i) {
    auto [v, res] = ccdb::read_reg(top, i);
    std::cerr << "Reg [" << std::dec << std::setw(2)<< i << "] : ";
    comm::sout32(std::cerr) << res << std::endl;
  }
  {
    auto [v, res] = ccdb::read_reg(top, 0xff);
    std::cerr << "Reg [PC] : ";
    comm::sout32(std::cerr) << res << std::endl;
  }
  // {
  //   for (uint32_t i = 0; i < 16; i += 4) {
  //     auto [v, res] = ccdb::read_mem(0x8000'0000U + i);
  //   comm::sout32(std::cerr, "") << res << " ";
  //   }
  //   std::cerr << std::endl;
  // }
  {
    std::cerr << "\n=== Inst Ring Buffer === " << std::endl;
    for (size_t i = 0; i < comm::instBuf.size(); i++) {
      comm::instBuf.atmod(i).printent(std::cerr);
    }
  }
  {
    std::cerr << "\n=== Mem Ring Buffer === " << std::endl;
    for (size_t i = 0; i < comm::memBuf.size(); i++) {
      comm::memBuf.atmod(i).printent(std::cerr);
    }
  }
  top->final();

  if (!comm::log_wavefile.empty()) {
    tfp->close();
  }
  return 0;
}

