#include "nlohmann/json.hpp"
#include "nlohmann/json_fwd.hpp"

#include <algorithm>
#include <cassert>
#include <cstdio>
#include <filesystem>
#include <format>
#include <fstream>
#include <getopt.h>
#include <iostream>
#include <memory>
#include <ostream>
#include <tuple>
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
abort_handler() {
  if (pccdb)
    pccdb->dump_print();
  if (pwave)
    pwave->close();
  exit(1);
}

void
print_stats() {
  // pccdb->dump_stats(std::cerr);
  ppmu->dump_stats(std::cerr);
  if (iCache) {
    auto const stat = iCache->stats();
    std::cerr << std::format(
                   ">> iCache:\n"
                   "   Hit {:d} Miss {:d} Total {:d} MissRate {:f}\n",
                   stat.hits, stat.misses, stat.accesses, stat.missRate())
              << std::endl;
  }
  if (!options::record_perf)
    return;
}

inline json
dump_stats() {
  json obj{};
  obj["l1icache"] = json({});
  obj["pmu"] = ppmu->stats_json();
  return obj;
}

inline json
dump_config() {
  json conf{};
  json obj{};
  if (iCache) {
    obj["size"] = iCache->size();
    obj["assoc"] = iCache->assoc();
    obj["blkSize"] = iCache->blksize();
    obj["latency"] = iCache->latency();
  }
  conf["l1icache"] = obj;
  return conf;
}

handler_t abortHandler = abort_handler;

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
  options::parse_args(argc, argv);

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
    /* size */ options::arch_config_val.at(options::ICacheSize),
    /* lineSize */ options::arch_config_val.at(options::ICacheBlock),
    /* assoc */ options::arch_config_val.at(options::ICacheAssoc));
  iCache = instCache.get();
#endif

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

  /** CONFIG BEGIN */
#if SOCMODE
  trace::DiffTester diff(mrom->dataVec());
  pdiff = &diff;
#else
  auto const diff =
    std::make_unique<trace::DiffTester>(unifiedMem->dataVec());
  pdiff = diff.get();
#endif
  trace::GuestTracer ccdb(options::elf_file);
  pccdb = &ccdb;

  const std::unique_ptr<trace::SoftPerfUnit> spmu{new trace::SoftPerfUnit};
  ppmu = spmu.get();

  std::ofstream statFile;
  std::ofstream confFile;
  if (options::record_perf) {
    try {
      std::filesystem::create_directories(options::outdir);
    } catch (const std::filesystem::filesystem_error& e) {
      std::cerr << "File create failed: " << e.what() << std::endl;
    }
    statFile.open(options::outdir + "/stats.json");
    confFile.open(options::outdir + "/config.json");
    assert(statFile.is_open() && confFile.is_open());
    confFile << std::setw(2) << dump_config() << std::endl;
    confFile.close();
  }
  /* ^^^ CONFIG END ^^^ */

  const size_t MaxCyc{options::max_cycles};
  size_t currCyc{0U};
  std::string retCause = "??";
  int retBad = 0;

  /** RESET SIMULATOR */
  single_reset(top, contextp, tfp);
  diff->copy();

  /** SIMULATION LOOP */
  while (currCyc < MaxCyc) {
    if (options::runtime_dump_opt.cycle_no)
      std::cerr << std::format("\r== @posedge of Cycle #{} ==", currCyc)
                << std::endl;
    currCyc++;

#if NVBENA
    nvboard_update();
#endif
    single_cycle(top, contextp, tfp);

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

  print_stats();

  if (options::record_perf) {
    statFile << std::setw(2) << dump_stats() << std::endl;
    statFile.close();
  }

#if NVBENA
  nvboard_quit();
#endif
  return retBad;
}
