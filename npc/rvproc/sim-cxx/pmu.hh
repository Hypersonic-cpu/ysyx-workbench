#pragma once

#include "probe.hh"
#include "rtl_defs.hh"
#include "stats_template/stats.hpp"

#include <cassert>
#include <cstdio>
#include <format>
#include <iostream>
#include <limits>
#include <utility>

namespace trace {

class SoftPerfUnit {
public:
  inline static const std::unordered_map<unsigned char, size_t> InstOpToIdx{
    {0b00000U, 0}, {0b00011U, 1}, {0b00100U, 2},  {0b00101U, 3},
    {0b01000U, 4}, {0b01100U, 5}, {0b01101U, 6},  {0b10100U, 7},
    {0b11000U, 8}, {0b11001U, 9}, {0b11010U, 10}, {0b11011U, 11},
    {0b11100U, 12}};

  inline static const std::vector<std::string> InstOpName = {
    "Load", "MiscM",  "OpImm", "Auipc",   "Store", "OpReg", "Lui",
    "OpFP", "Branch", "Jalr",  "Reserve", "Jal",   "System"};

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

  enum XBarBreakdown { XBarIdle = 0, XBarBlocking, XBarServing };

  inline static const std::unordered_map<XBarBreakdown, std::string>
    XBarBreakdownName{{XBarBreakdown::XBarIdle, "Idle"},
                      {XBarBreakdown::XBarBlocking, "Blocking"},
                      {XBarBreakdown::XBarServing, "Serving"}};

  enum class CacheBreakdown { Miss = 0, Hit };

  inline static const std::unordered_map<CacheBreakdown, std::string>
    CacheBreakdownName{{CacheBreakdown::Miss, "Miss"},
                       {CacheBreakdown::Hit, "Hit"}};

private:
  // DistriDelta<int64_t> pcjmp;
  DistriBase<uint64_t> ifcyc;
  DistriDelta<uint64_t> lscyc;
  DistriVec<DistriBase<uint64_t>, uint64_t> instcyc;

  std::pair<bool, uint64_t> flushedRec;
  DistriBase<uint64_t> recoverTime;

  ClassifiedStats<CycBreakdown> cycStatus;
  ClassifiedStats<InstBreakdown> instStatus;
  ClassifiedStats<XBarBreakdown> memRdStatus;
  ClassifiedStats<XBarBreakdown> memWrStatus;

  ClassifiedStats<CacheBreakdown> cacheRates;

  enum BpBreakdown {
    BpCorrect = 0,
    BpBtbMiss,
    BpWrongDir,
    BpWrongTarget
  };

  inline static const std::unordered_map<BpBreakdown, std::string>
    BpBreakdownName{{BpCorrect, "Correct"},
                    {BpBtbMiss, "BtbMiss"},
                    {BpWrongDir, "WrongDir"},
                    {BpWrongTarget, "WrongTgt"}};

  ClassifiedStats<BpBreakdown> bpStats;

  std::vector<StatsBase*> statslist{&ifcyc,       &lscyc,       &instcyc,
                                    &cycStatus,   &instStatus,  &memRdStatus,
                                    &memWrStatus, &recoverTime, &cacheRates,
                                    &bpStats};

  using iboard_t = std::tuple<addr_t, size_t, uint64_t>;
  std::list<iboard_t> instboard;
  using ifetch_t = std::tuple<addr_t, uint64_t>;
  std::list<ifetch_t> ifetchboard;
  using icache_t = std::tuple<addr_t, tick_t>;
  std::list<icache_t> icacheboard;

public:
  SoftPerfUnit()
      : instboard{}
      , ifetchboard{}
      , flushedRec{false, 0}
      , ifcyc(0, 100, 10, "Inst Fetch Cycles")
      , lscyc(0, 100, 10, std::numeric_limits<int64_t>::max(),
              "Load Store Cycles")
      , instcyc(InstOpName.size(), 0, 100, 10,
                std::numeric_limits<int64_t>::max(), "Inst Cats", InstOpName)
      , recoverTime(0, 60, 3, "BrRecover Time")
      // , pcjmp(0, 1024, 64, ResetVector, "PC Jump Distance")
      , instStatus("InstBreakdown", InstBreakdownName)
      , cycStatus("BlockedCause", CycBreakdownName)
      , memRdStatus("XBarReadUsage", XBarBreakdownName)
      , memWrStatus("XBarWriteUsage", XBarBreakdownName)
      , cacheRates("L1ICache", CacheBreakdownName)
      , bpStats("BranchPred", BpBreakdownName) {}

  void
  dump_stats(std::ostream& os = std::cout) const {
    os << std::format("Cycles {:d}\n  InstRet {:d} IPC {:.6f} StallCyc {:d}",
                      get_cycles(), get_instret(), get_ipc(),
                      get_cycles() - get_instret())
       << std::endl;
    for (auto const& ptr : statslist) {
      ptr->dump_stats(os);
    }
    auto bp_total = bpStats.get_samples();
    if (bp_total > 0) {
      auto bp_correct = bpStats.at(BpCorrect);
      os << std::format("BrPred Accuracy : {:.2f}% ({:d}/{:d})",
                        100.0 * bp_correct / bp_total, bp_correct, bp_total)
         << std::endl;
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
    v_assert(!ifetchboard.empty(), "Cannot find pc @", pc, "in IF Pipeline");
    while (std::get<0>(ifetchboard.front()) != pc) {
      ifetchboard.pop_front();
      v_assert(!ifetchboard.empty(), "Cannot find pc @", pc,
               "in IF Pipeline");
    }
    ifcyc.sample(curr_tick() - std::get<1>(ifetchboard.front()));
    ifetchboard.pop_front();
  }

  void
  notifyIFFetch(addr_t pc) {
    ifetchboard.emplace_back(pc, curr_tick());
  }

  void
  notifyDecode(addr_t pc, unsigned char op) {
    instboard.emplace_back(pc, InstOpToIdx.at(op), curr_tick());
    if (flushedRec.first) {
      recoverTime.sample(curr_tick() - flushedRec.second);
      flushedRec.first = false;
    }
  }

  void
  notifyLSReq(addr_t a) {
    lscyc.updlast(curr_tick());
  }

  void
  notifyLSResp(addr_t a) {
    lscyc.sample(curr_tick());
  }

  void
  notifyFlush() {
    flushedRec = {true, curr_tick()};
  }

  void
  notifyMemXBar(bool is_write, uint64_t last_time, uint32_t time_usage) {
    auto curr_time = curr_tick();
    auto& sel = is_write ? memWrStatus : memRdStatus;
    if (last_time < curr_time) {
      sel.sample(XBarBreakdown::XBarIdle, curr_time - last_time);
    } else if (last_time >= curr_time) {
      sel.sample(XBarBreakdown::XBarBlocking, last_time - curr_time);
    }
    sel.sample(XBarBreakdown::XBarServing, time_usage);
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

  void
  notifyCacheResp(addr_t addr, bool is_hit, uint16_t id) {
    // std::cerr << std::format("Resp @{:08x} Hit {:d}\n", addr, is_hit);
    assert(id == 0);
    auto& front = icacheboard.front();
    auto [f_addr, f_tick] = front;
    v_assert(f_addr == addr, "Cache access queue front", f_addr,
             "!= resp. addr", addr);
    if (is_hit) {
      cacheRates.sample(CacheBreakdown::Hit);
    } else {
      cacheRates.sample(CacheBreakdown::Miss);
      // TODO: Sample miss penalty
    }
    icacheboard.pop_front();
  }

  void
  notifyCacheReq(addr_t addr, uint16_t id) {
    assert(id == 0);
    icacheboard.emplace_back(addr, curr_tick());
  }

  void
  notifyBrOutcome(bool pred_taken, bool actual_taken,
                  uint32_t pred_target, uint32_t actual_target,
                  bool btb_hit) {
    if (!btb_hit && actual_taken) {
      bpStats.sample(BpBtbMiss);
    } else if (pred_taken != actual_taken) {
      bpStats.sample(BpWrongDir);
    } else if (pred_taken && actual_taken && pred_target != actual_target) {
      bpStats.sample(BpWrongTarget);
    } else {
      bpStats.sample(BpCorrect);
    }
  }

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
    return get_cycles() ? static_cast<double>(get_instret())
                            / static_cast<double>(get_cycles())
                        : 0.0;
  }
};

} // namespace trace
