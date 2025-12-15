#include <cassert>
#include <format>
#include <getopt.h>
#include <iostream>
#include <memory>
#include <verilated.h>
#include <verilated_fst_c.h>

#include "VysyxSoCFull.h"

#include "ccdb.hh"
#include "difftest.hh"
#include "options.hh"
#include "probe.hh"
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

const TOP_NAME* trace::ptop = nullptr;
trace::IFState trace::last_state = trace::IFState::Start;

int
main(int argc, char* argv[]) {
  Verilated::commandArgs(argc, argv);
  assert(argc >= 2);

  auto mromBin = std::make_shared<RuntimeBin>(argv[1], 0x2000'0000U);
  mrom = mromBin.get();

  options::parse_args(argc, argv);
  if (options::wave_enable) {
    assert(!options::wave_file.empty());
  }

  const std::unique_ptr<VerilatedContext> contextp{new VerilatedContext};

  const std::unique_ptr<TOP_NAME> top{new TOP_NAME{contextp.get(), "TOP"}};
  trace::ptop = top.get();
  trace::FstTracer<options::wave_enable> tfp(options::wave_file);
  if constexpr (options::wave_enable) {
    Verilated::traceEverOn(true);
    top->trace(tfp.get(), 99);
    tfp.open();
  }

  single_reset(top, contextp, tfp);

  constexpr size_t MaxCyc = 1000'000U;
  size_t currCyc{0U};
  std::string retCause = "??";
  int retBad = 0;

  trace::DiffTester<options::diff_enable> diff(mrom->dataVec());
  // Force RESET_VECTOR = 0x2000'0000 in NEMU
  diff.copy();
  trace::GuestTracer<options::gdbg_enable> ccdb(options::elf_file);

  while (currCyc < MaxCyc) {
    if (options::runtime_dump_opt.cycle_no)
      std::cerr << std::format("\r== @posedge of Cycle #{} ==", currCyc)
                << std::endl;

    currCyc++;

    trace::upd_ifs_mcstate();
    single_cycle(top, contextp, tfp);

    ccdb.inst_trace();

    if (auto mismatch = diff.test_on_commit(); !mismatch.empty()) {
      for (auto const& [id, golden, real] : mismatch) {
        std::cerr << std::format(
                       "Reg {:>2d} mismatch: golden {:>8x} real ${:>8x}", id,
                       golden, real)
                  << std::endl;
      }

      ccdb.dump_print();

      retCause = "DiffTest failed";
      retBad = 1;
      break;
    }

    if (contextp->gotFinish()) {
      retCause = "Ecall";
      retBad = 0;
      break;
    }
    if (currCyc == MaxCyc) {
      retCause = "Max cycles reached";
      retBad = 1;
    }
  }
  top->final();

  std::cerr << std::format(ANSI_YELLOW
                           "== Exit @ cycle {:d} : {:s} ==" ANSI_NONE,
                           currCyc, retCause)
            << std::endl;
  return retBad;
}
