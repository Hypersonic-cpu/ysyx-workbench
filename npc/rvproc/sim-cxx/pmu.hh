#pragma once

#include "probe.hh"
#include "stats.hh"

namespace trace {

class SoftPerfUnit {
public:
  inline static const std::unordered_map<unsigned char, size_t> InstOpToIdx{
    {0b00000U, 0}, {0b00011U, 1}, {0b00100U, 2},  {0b00101U, 3},
    {0b01000U, 4}, {0b01100U, 5}, {0b10100U, 6},  {0b01101U, 7},
    {0b11000U, 8}, {0b11001U, 9}, {0b11011U, 10}, {0b11100U, 11}};

  inline static const std::vector<std::string> InstOpName = {
    "Load", "Misc-Mem", "OpImm",  "Auipc", "Store", "OpReg",
    "OpFP", "Lui",      "Branch", "Jalr",  "Jal",   "System"};

  // Total count = sim cycles. Exclusive
  enum CycBreakdown {
    NoStall = 0,
    IfuStall,
    LsuStall,
    BranchMispred,
    ReadAfterWrite
  };

  inline static const std::unordered_map<CycBreakdown, std::string>
    CycBreakdownName{{CycBreakdown::NoStall, "NoStall"},
                     {CycBreakdown::IfuStall, "NoInst"},
                     {CycBreakdown::LsuStall, "LsuStall"},
                     {CycBreakdown::BranchMispred, "BranchMispred"},
                     {CycBreakdown::ReadAfterWrite, "RAW"}};

  // Total count = Fetched. Exclusive
  enum InstBreakdown { Commit = 0, NotUsed };

  inline static const std::unordered_map<InstBreakdown, std::string>
    InstBreakdownName{{InstBreakdown::Commit, "Commit"},
                      {InstBreakdown::NotUsed, "NotUsed"}};

  enum ArbiterBreakdown {
    ArbiterIdle = 0,
    ArbiterIRead,
    ArbiterDRead,
    ArbiterDWrite
  };
  inline static const std::unordered_map<ArbiterBreakdown, std::string>
    ArbiterBreakdownName{{ArbiterBreakdown::ArbiterIdle, "None"},
                         {ArbiterBreakdown::ArbiterIRead, "IRead"},
                         {ArbiterBreakdown::ArbiterDRead, "DRead"},
                         {ArbiterBreakdown::ArbiterDWrite, "DWrite"}};

private:
  DistriDelta<int64_t> pcjmp;
  DistriBase<uint64_t> ifcyc;
  DistriDelta<uint64_t> lscyc;
  DistriVec<DistriBase<uint64_t>, uint64_t> instcyc;

  ClassifiedStats<CycBreakdown> cycStatus;
  ClassifiedStats<InstBreakdown> instStatus;
  ClassifiedStats<ArbiterBreakdown> memStatus;

  std::vector<StatsBase*> statslist{&ifcyc, &lscyc, &instcyc, &cycStatus,
                                    &instStatus};

  using iboard_t = std::tuple<addr_t, size_t, uint64_t>;
  std::list<iboard_t> instboard;
  using ifetch_t = std::tuple<addr_t, uint64_t>;
  std::list<ifetch_t> ifetchboard;

public:
  SoftPerfUnit()
      : instboard{}
      , ifetchboard{}
      , ifcyc(0, 100, 10, "Inst Fetch Cycles")
      , lscyc(0, 100, 10, std::numeric_limits<int64_t>::max(),
              "Load Store Cycles")
      , instcyc(InstOpName.size(), 0, 200, 20,
                std::numeric_limits<int64_t>::max(), "Inst Cats", InstOpName)
      , pcjmp(0, 1024, 64, ResetVector, "PC Jump Distance")
      , instStatus("InstBreakdown", InstBreakdownName)
      , cycStatus("BlockedCause", CycBreakdownName)
      , memStatus("BusUsage", ArbiterBreakdownName) {}

  void
  dump_stats(std::ostream& os = std::cout) const {
    // Should commit 1 inst per cycle
    os << std::format("Cycles {:d}\n  InstRet {:d} IPC {:.6f} StallCyc {:d}",
                      get_cycles(), get_instret(), get_ipc(),
                      get_cycles() - get_instret())
       << std::endl;
    for (auto const& ptr : statslist) {
      ptr->dump_stats(os);
    }
  }

  json
  stats_json() const {
    json ret{};
    for (auto const& ptr : statslist) {
      ret[ptr->name()] = ptr->gen_json();
    }
    ret["ipc"] = get_ipc();
    return ret;
  }

  void
  notifyIFRecvd(addr_t pc) {
    // using namespace std;
    // cout << format("IF Recv {:x}", pc) << endl;
    while (std::get<0>(ifetchboard.front()) != pc) {
      // cout << format("Front is {:x}", std::get<0>(ifetchboard.front())) <<
      // endl;
      ifetchboard.pop_front();
      v_assert(!ifetchboard.empty(), "Cannot find pc @", pc,
               "in IF Pipeline");
    }
    ifcyc.sample(curr_tick() - std::get<1>(ifetchboard.front()));
    ifetchboard.pop_front();

    // cout << "==> IF Queue ";
    // for (auto const& [pcs , time]: ifetchboard) {
    //   cout << format("[{:08x} @ {}] ", pcs, time);
    // }
    // cout << endl;
    // pcjmp.sample(static_cast<int64_t>(pc));
  }

  void
  notifyIFFetch(addr_t pc) {
    ifetchboard.emplace_back(pc, curr_tick());
  }

  void
  notifyDecode(addr_t pc, unsigned char op) {
    instboard.emplace_back(pc, InstOpToIdx.at(op), curr_tick());
  }

  void
  notifyLSReq(addr_t a) {
    lscyc.updlast(curr_tick());
  }

  void
  notifyLSResp(addr_t a) {
    lscyc.sample(curr_tick());
  }

  /**
   * Receive notify signal from WBU in EACH CYCLE.
   * @param pc The committed instruction PC
   * @stalltp Stall type, =0 when WBU is valid this cycle.
   *   Note that valid === fire for WBU.
   */
  void
  notifyCommit(addr_t pc, unsigned char stalltp) {
    auto cause = static_cast<CycBreakdown>(stalltp);
    cycStatus.sample(cause);
    if (cause != CycBreakdown::NoStall)
      return;

    auto it = std::find_if(
      instboard.begin(), instboard.end(),
      [&pc](const iboard_t& ib) { return std::get<0>(ib) == pc; });
    v_assert(it != instboard.end(), "Cannot find pc", pc, "in instboard");
    auto const [pc_, tp, t0] = *it;
    auto const deltat = curr_tick() - t0;
    instcyc.sample(tp, deltat);
    instboard.erase(it);

    // The pipeline is in order, so we can remove instructions
    // that was ISSUED before the matched one.
    auto const remove_cnt = std::erase_if(
      instboard, [&t0](const iboard_t& ib) { return std::get<2>(ib) < t0; });

    // Since not break, must commit 1 insts.
    instStatus.sample(Commit, 1);
    instStatus.sample(NotUsed, remove_cnt);
  }

  // void probeArbiter() {
  //   memStatus.sample(state);
  // }

  void
  reset_stats() {
    for (auto const& ptr : statslist) {
      ptr->reset_stats();
    }
  }

  size_t
  get_cycles() const noexcept {
    return cycStatus.get_samples();
  }

  size_t
  get_instret() const noexcept {
    return instStatus.at(InstBreakdown::Commit);
  }

  double
  get_ipc() const noexcept {
    return get_cycles() ? static_cast<double>(get_instret()) /
                            static_cast<double>(get_cycles())
                        : 0.0;
  }
};

} // namespace trace
