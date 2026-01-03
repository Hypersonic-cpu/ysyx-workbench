#include <algorithm>
#include <cassert>
#include <format>
#include <getopt.h>
#include <iostream>
#include <memory>
#include <ostream>
#include <vector>
#include <verilated.h>
#include <verilated_fst_c.h>

#if SOCMODE
#include "VysyxSoCFull.h"
#include "VysyxSoCFull___024root.h"
#else
#include "VrvCore.h"
#include "VrvCore___024root.h"
#include "cacheSim/CacheSimulator.hh"
#endif

#include "ccdb.hh"
#include "difftest.hh"
#include "options.hh"
#include "pmu.hh"
#include "probe.hh"
#include "runtime.hh"
#include "wave.hh"

#if NVBENA
#include <nvboard.h>

void nvboard_bind_all_pins(TOP_NAME* top);
#endif

trace::FstTracer* pwave = nullptr;
const TOP_NAME* trace::ptop = nullptr;

void
dump_handler() {
  if (pccdb)
    pccdb->dump_print();
  if (pwave)
    pwave->close();
  exit(1);
}

void
dump_stats() {
  // pccdb->dump_stats(std::cerr);
  // ppmu->dump_stats(std::cerr);
  if (iCache) {
    auto const stat = iCache->stats();
    std::cerr << std::format(
                   "iCache: Hit {:d} Miss {:d} Total {:d} MissRate {:f}\n",
                   stat.hits, stat.misses, stat.accesses, stat.missRate())
              << std::endl;
  }
}

handler_t dumpHandler = dump_handler;

inline void
single_cycle(const std::unique_ptr<TOP_NAME>& top,
             const std::unique_ptr<VerilatedContext>& context,
             const trace::FstTracer& wave) {

  top->clock = 1;
  context->timeInc(1);
  top->eval();
  wave.dump(context->time());

  top->clock = 0;
  context->timeInc(1);
  top->eval();
  wave.dump(context->time());
}

inline void
single_reset(const std::unique_ptr<TOP_NAME>& top,
             const std::unique_ptr<VerilatedContext>& context,
             const trace::FstTracer& wave) {
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
  assert(argc >= 2);

#if SOCMODE
  auto mromBin = std::make_shared<RuntimeBin>(
    std::vector<ureg_t>(10U, 0xbadc0de), 0x2000'0000U, "MROM");
  mrom = mromBin.get();

  auto flashBin =
    std::make_shared<RuntimeBin>(argv[1], 0x0000'0000U, "Flash");
  flash = flashBin.get();

  auto psramBin = std::make_shared<RuntimeBin>(
    std::vector<ureg_t>((4U << 20U) / 4, 0xbadc0de), 0x0000'0000U, "Psram");
  psram = psramBin.get();

  auto sdramBin = std::make_shared<RuntimeBin>(
    std::vector<ureg_t>((4U << 20U) / 4, 0xc0de0bad), 0x0000'0000U, "Sdram");
  sdram = sdramBin.get();

  auto vmemBin = std::make_shared<RuntimeBin>(
    "/mnt/hgfs/Arch-PA/JiaoTongUniversity.bin", 0x0000'0000U, "VMem");
  vmem = vmemBin.get();
#else
  auto uMem = std::make_shared<RuntimeBin>(argv[1], (4U << 20) / 4,
                                           0x8000'0000LLU, "UnifiedMem");
  unifiedMem = uMem.get();

  auto instCache = std::make_unique<cacheSim::CacheSimulator>(
    /* size */ 1024, /* lineSize */ 16, /* assoc */ 1);
  iCache = instCache.get();
  // iCache = nullptr;
#endif

  options::parse_args(argc, argv);
  if (options::wave_enable) {
    assert(!options::wave_file.empty());
  }

  const std::unique_ptr<VerilatedContext> contextp{new VerilatedContext};
  contextp->commandArgs(argc, argv);

  const std::unique_ptr<TOP_NAME> top{new TOP_NAME{contextp.get(), "TOP"}};
  trace::ptop = top.get();
  trace::FstTracer tfp(options::wave_file);
  pwave = &tfp;
  if constexpr (options::wave_enable) {
    Verilated::traceEverOn(true);
    top->trace(tfp.get(), 99);
    tfp.open();
  }

#if NVBENA
  nvboard_bind_all_pins(top.get());
  nvboard_init();
#endif

  const size_t MaxCyc{options::max_cycles};
  size_t currCyc{0U};
  std::string retCause = "??";
  int retBad = 0;

#if SOCMODE
  trace::DiffTester diff(mrom->dataVec());
#else
  auto const diff = std::make_unique<trace::DiffTester>(unifiedMem->dataVec());
  pdiff = diff.get();
#endif
  trace::GuestTracer ccdb(options::elf_file);
  pccdb = &ccdb;

  const std::unique_ptr<trace::SoftPerfUnit> spmu{new trace::SoftPerfUnit};
  ppmu = spmu.get();

  single_reset(top, contextp, tfp);
  // Force RESET_VECTOR of NEMU = current PC
  diff->copy();

  while (currCyc < MaxCyc) {
    if (options::runtime_dump_opt.cycle_no)
      std::cerr << std::format("\r== @posedge of Cycle #{} ==", currCyc)
                << std::endl;

    currCyc++;

#if NVBENA
    nvboard_update();
#endif
    // for (int i = 0 ; i < 4; i++) {
    //   printf("%08x ", unifiedMem->dataVec().at(i));
    // }
    // printf("\n");

    single_cycle(top, contextp, tfp);

    ccdb.inst_trace();

    if (auto mismatch = diff->test_on_commit(); !mismatch.empty()) {
      for (auto const& [id, golden, real] : mismatch) {
        std::cerr << std::format(
                       "Reg {:>2d} mismatch: golden {:>8x} real {:>8x}", id,
                       golden, real)
                  << std::endl;
      }

      ccdb.dump_print();

      retCause = "DiffTest failed";
      retBad = 1;
      break;
    }

    if (contextp->gotFinish()) {
      retCause = "Ebreak";
      retBad = 0;
      break;
    }
    if (currCyc == MaxCyc) {
      retCause = "Max cycles reached";
      retBad = 1;
    }
  }

final:

  auto const lastPC{trace::read_reg(trace::RegNum)};
  top->final();

  std::cerr << std::format(ANSI_YELLOW
                           "== Exit @ pc {:>08x} : {:s} ==" ANSI_NONE,
                           lastPC, retCause)
            << std::endl;
  auto instNum = ccdb.get_inst_count();
  auto ipc = static_cast<double>(instNum) / currCyc;
  std::cout << std::format(ANSI_YELLOW
                           "== #cyc {:d} #inst {:d} IPC {:6f}" ANSI_NONE,
                           currCyc, instNum, ipc)
            << std::endl;

  dump_stats();
#if NVBENA
  nvboard_quit();
#endif
  return retBad;
}
