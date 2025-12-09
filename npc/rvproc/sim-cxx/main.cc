#include <cassert>
#include <cstdlib>
#include <ctime>
#include <format>
#include <getopt.h>
#include <iomanip>
#include <ios>
#include <iostream>
#include <iterator>
#include <memory>
#include <numeric>

#include <verilated.h>
#include <verilated_fst_c.h>

#include "VrvCore.h"
#include "ccdb.hh"
#include "difftest.hh"
#include "disasm.hh"
#include "probe.hh"

#define ANSI_Red "\033[31m"
#define ANSI_Green "\033[32m"
#define ANSI_Yellow "\033[33m"
#define ANSI_Blue "\033[34m"
#define ANSI_None "\033[0m"

void
parse_args(int argc, char *argv[]) {
  constexpr struct option table[] = {
    {"print-mem", no_argument, NULL, 'm'},
    {"print-inst", no_argument, NULL, 'i'},
    {"print-dev", no_argument, NULL, 'd'},
    {"print-frame", no_argument, NULL, 'f'},
    {"print-cycle", no_argument, NULL, 'c'},
    {"fast-mode", no_argument, NULL, 'F'},
    {"no-difftest", no_argument, NULL, 'n'},
    {"log", required_argument, NULL, 'l'},
    {"elf", required_argument, NULL, 'e'},
    {"help", no_argument, NULL, 'h'},
    {0, 0, NULL, 0},
  };
  int o;
  while ((o = getopt_long(argc, argv, "-hmidfcnTl:e:", table, NULL)) != -1) {
    switch (o) {
    case 'm':
      ccdb::runtime_dump_opt.mem_buf = true;
      break;
      // case 'd': ccdb::runtime_dump_opt. = true; break;
    case 'f':
      ccdb::runtime_dump_opt.frame_stk = true;
      break;
    case 'i':
      ccdb::runtime_dump_opt.inst_buf = true;
      break;
    case 'l':
      comm::log_wavefile = std::string(optarg);
      comm::log_ena = true;
      break;
    case 'e':
      comm::elf_file = optarg;
      break;
    case 'n':
      diff::enable = false;
      break;
    case 'F':
      comm::fast = true;
      break;
    case 'c':
      ccdb::runtime_print_cycle = true;
      break;
    default:
      std::cerr << ANSI_Red << "Invalid Arguments.\n" << ANSI_None << std::endl;
      exit(1);
    }
  }
}

inline void
single_cycle(const std::unique_ptr<TOP_NAME> &top,
             const std::unique_ptr<VerilatedContext> &context,
             const std::unique_ptr<VerilatedFstC> &fstwave) {

  top->clock = 1;
  context->timeInc(1);
  top->eval();
  if (comm::log_ena)
    fstwave->dump(context->time());
  top->clock = 0;
  context->timeInc(1);
  top->eval();
  if (comm::log_ena)
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
  if (comm::log_ena)
    fstwave->dump(context->time());
  top->clock = 0;
  top->reset = 0; // cancel reset @ falling edge
  context->timeInc(1);
  top->eval();
  if (comm::log_ena)
    fstwave->dump(context->time());
}

ccdb::ptop_t ccdb::top = nullptr;

int
main(int argc, char *argv[]) {
  parse_args(argc, argv);

  const std::unique_ptr<VerilatedContext> contextp{new VerilatedContext};

  if (comm::log_ena) {
    Verilated::traceEverOn(true);
  }
  // VerilatedFstC *tfp = new VerilatedFstC;
  const std::unique_ptr<VerilatedFstC> tfp{new VerilatedFstC};

  const std::unique_ptr<TOP_NAME> top{new TOP_NAME{contextp.get(), "TOP"}};
  ccdb::top = top.get();

  if (comm::log_ena) {
    // Trace 99 levels of hierarchy (or see below)
    top->trace(tfp.get(), 99);
    // tfp->dumpvars(1, "t"); // trace 1 level under "t"
    tfp->open(comm::log_wavefile.c_str());
  }

  if (!comm::fast) {
    ccdb::trace_init();
  }
  single_reset(top, contextp, tfp);

  if (diff::enable) {
    diff::init();
  }

  constexpr size_t MaxCyc{~284U};
  size_t currCyc{0U};
  bool exitBad{false};
  while (!contextp->gotFinish() && currCyc < MaxCyc) {
    if (ccdb::runtime_print_cycle) {
      std::cerr << std::format("== @posedge of Cycle #{} ==", currCyc)
                << std::endl;
    }
    // WARN: Skipping cycle 0
    if (diff::enable) {
      // diff::state_checker.force_state(ccdb::read_ifs_mcstate());
      if (ccdb::npc_inst_commit()) {
        diff::copy();
        comm::mem_write_buf = {0, 0, 0};
      }
    } // Comes before exec

    if (!comm::fast) {
      ccdb::inst_trace();
    }

    ccdb::record_ifs_mcstate();
    // NOTE: Dut Upd Here
    single_cycle(top, contextp, tfp);

    // std::cout << std::format("After  exec: mcstate = {}\n",
    //                          (int)ccdb::read_ifs_mcstate());

    if (diff::enable && currCyc) {
      if (ccdb::npc_inst_commit())
        diff::iota();

      if (ccdb::npc_inst_commit()) {
        // TODO: Memory check of writes to device
        auto const &dut = comm::mem_write_buf;
        auto [v, ref] = diff::match_memwr(dut);
        if (!v) {
          std::cerr
            << ANSI_Red
            << std::format(
                 ANSI_Red
                 "Memory Write Mismatch: " ANSI_None
                 "expected (addr, data, mask) = ({:08x}, {:08x}, {:04b}) "
                 "got = ({:08x}, {:08x}, {:04b})",
                 ref.aligned, ref.data, ref.mask, dut.aligned, dut.data,
                 dut.mask)
            << std::endl;
          exitBad = true;
          break;
        }
      }

      if (ccdb::npc_inst_commit()) {
        auto diffvec = diff::match();
        if (!diffvec.empty()) {
          for (const auto &[id, ref, dut] : diffvec) {
            std::cerr << ANSI_Red
                      << std::format("Mismatch reg {:d} ({}) : expected ",
                                     (int)id, comm::RegName.at(id))
                      << ANSI_None << "expected ";
            comm::sout32(std::cerr) << ref << " got ";
            comm::sout32(std::cerr) << dut << std::endl;
          }
          ccdb::dump_print(ccdb::DumpPrint{false, true, true, false, false});
          exitBad = true;
          break;
        }
      }
    }
    currCyc++;
  }
  top->final();

  if (comm::log_ena) {
    tfp->close();
  }

  if (currCyc == MaxCyc)
    exitBad |= true;
  else
    std::cerr << std::format(ANSI_Green
                             "== Exit SimLoop @ Cycle #{} ==" ANSI_None,
                             currCyc)
              << std::endl;

  return exitBad;
}
