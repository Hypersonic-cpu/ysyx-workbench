#include "nlohmann/json.hpp"
#include "nlohmann/json_fwd.hpp"
#include "rtl_defs.hh"

#include <cassert>
#include <cstdio>
#include <filesystem>
#include <format>
#include <fstream>
#include <iostream>
#include <memory>
#include <ostream>
#include <string>
#include <verilated.h>
#include <verilated_fst_c.h>

#if SOCMODE
#include "VysyxSoCFull.h"
#include "VysyxSoCFull___024root.h"
#else
#include "VrvCore.h"
#include "VrvCore___024root.h"
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

static std::ofstream statFile;
static std::ofstream confFile;

static tick_t g_tick{0};
tick_t
curr_tick() noexcept {
  return g_tick;
}

void
abort_handler() {
  if (pccdb)
    pccdb->dump_print();
  if (pwave)
    pwave->close();
  exit(1);
}

void
reset_all_stats() {
  if (ppmu)
    ppmu->reset_stats();
}

void
print_stats() {
  // pccdb->dump_stats(std::cerr);
  ppmu->dump_stats(std::cerr);
}

inline json
dump_stats() {
  json obj{};
  obj["pmu"] = ppmu->stats_json();
  obj["image"] = options::binary_img;
  return obj;
}

inline json
dump_config() {
  json conf{};
  conf["image"] = options::binary_img;
#if SOCMODE
  conf["mode"] = std::string("soc");
#else
  conf["sdram"] = json({{"latency", MemLatency}, {"burstlat", MemBstLat}});
  conf["mode"] = std::string("npc");
#endif
  return conf;
}

void
dump_all_stats() {
  if (options::record_perf) {
    statFile << std::setw(2) << dump_stats() << std::endl;
  }
}

handler_t abortHandler = abort_handler;
handler_t resetAllStats = reset_all_stats;
handler_t dumpAllStats = dump_all_stats;

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
  options::binary_img = std::string(argv[1]);
  options::parse_args(argc, argv);

#if SOCMODE
  auto mromBin = std::make_shared<RuntimeBin>(
    std::vector<ureg_t>(10U, 0xbadc0de), 0x2000'0000U, "MROM");
  mrom = mromBin.get();

  auto flashBin =
    std::make_shared<RuntimeBin>(options::binary_img, 0x0000'0000U, "Flash");
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
  auto uMem = std::make_unique<RuntimeBin>(
    options::binary_img, (128U << 20) / 4, 0x8000'0000LLU, "UnifiedMem");
  unifiedMem = uMem.get();
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
  auto const diff =
    std::make_unique<trace::DiffTester>(mrom->dataVec());
  pdiff = diff.get();
#else
  auto const diff =
    std::make_unique<trace::DiffTester>(unifiedMem->dataVec());
  pdiff = diff.get();
#endif
  trace::GuestTracer ccdb(options::elf_file);
  pccdb = &ccdb;

  auto const spmu = std::make_unique<trace::SoftPerfUnit>();
  ppmu = spmu.get();

  if (options::record_perf) {
    v_warn(false, "Record perf >>", options::outdir);
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
  /** CONFIG END */

  const size_t MaxCyc{options::max_cycles};
  // size_t currCyc{0U};
  std::string retCause = "??";
  int retBad = 0;

  /** RESET SIMULATOR */
  single_reset(top, contextp, tfp);
  diff->copy();

  /** SIMULATION LOOP */
  while (true) {
    if (options::runtime_dump_opt.cycle_no)
      std::cerr << std::format("\r== @posedge of Cycle #{} ==", curr_tick())
                << std::endl;

#if NVBENA
    nvboard_update();
#endif
    single_cycle(top, contextp, tfp);

    if (auto mismatch = diff->test_on_commit(); !mismatch.empty()) {
      for (auto const& [id, golden, real] : mismatch) {
        std::cerr << std::format(
          "Reg {:>2d} mismatch: golden {:>8x} real {:>8x}", id, golden, real)
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
    if (g_tick == MaxCyc) {
      retCause = "Max cycles reached";
      retBad = 1;
      break;
    }

    g_tick++;
  }

final:

  // auto const lastPC{trace::read_reg(trace::RegNum)};
  top->final();

  std::cerr << std::format(ANSI_YELLOW
                           "== Exit @ cyc #{:>12d} : {:s} ==" ANSI_NONE,
                           curr_tick(), retCause)
            << std::endl;
  auto instNum = spmu->get_instret();
  auto cycleNum = spmu->get_cycles();
  auto ipc = static_cast<double>(instNum) / static_cast<double>(cycleNum);
  std::cout << std::format(ANSI_YELLOW
                           "== #cyc {:d} #inst {:d} IPC {:6f} ==" ANSI_NONE,
                           cycleNum, instNum, ipc)
            << std::endl;
  std::cout << std::format(ANSI_YELLOW "== CSR: InstRet {:d} ==" ANSI_NONE,
                           trace::read_double_csr(trace::CsrSel::MInstreth,
                                                  trace::CsrSel::MInstret))
            << std::endl;

  print_stats();

  if (options::record_perf) {
    statFile.close();
  }

#if NVBENA
  nvboard_quit();
#endif
  return retBad;
}
