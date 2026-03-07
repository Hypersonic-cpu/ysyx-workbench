#pragma once

#include "rtl_defs.hh"
#include "stats_template/stats.hpp"

#include <array>
#include <cstdint>
#include <limits>
#include <list>
#include <unordered_map>
#include <utility>
#include <vector>

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
  ClassifiedStats<CacheBreakdown> dcacheRates;

  enum BpBreakdown { BpCorrect = 0, BpBtbMiss, BpWrongDir, BpWrongTarget };

  inline static const std::unordered_map<BpBreakdown, std::string>
    BpBreakdownName{{BpCorrect, "Correct"},
                    {BpBtbMiss, "BtbMiss"},
                    {BpWrongDir, "WrongDir"},
                    {BpWrongTarget, "WrongTgt"}};

  ClassifiedStats<BpBreakdown> bpStats;

  struct BpPerPC {
    uint32_t correct{0};
    uint32_t wrong{0};
  };
  mutable std::unordered_map<uint32_t, BpPerPC> bpPerPC;

#if DBGENA
  std::vector<StatsBase*> statslist{&ifcyc,       &lscyc,       &instcyc,
                                    &cycStatus,   &instStatus,  &memRdStatus,
                                    &memWrStatus, &recoverTime, &cacheRates,
                                    &dcacheRates, &bpStats};
#else
  std::vector<StatsBase*> statslist{&cycStatus, &instStatus};
#endif

  using iboard_t = std::tuple<addr_t, size_t, uint64_t>;
  std::list<iboard_t> instboard;
  using ifetch_t = std::tuple<addr_t, uint64_t>;
  std::list<ifetch_t> ifetchboard;
  using icache_t = std::tuple<addr_t, tick_t>;
  std::list<icache_t> icacheboard;

  struct IFEvent {
    char type;
    addr_t pc;
    uint64_t tick;
  };
  static constexpr size_t kIFRingSz = 64;
  std::array<IFEvent, kIFRingSz> ifRing{};
  size_t ifRingIdx{0};
  void
  ifRingPush(char t, addr_t pc, uint64_t tk) {
    ifRing[ifRingIdx % kIFRingSz] = {t, pc, tk};
    ++ifRingIdx;
  }
  void ifRingDump() const;

public:
  SoftPerfUnit();

  void dump_stats(std::ostream& os = std::cout) const;
  json stats_json() const;

  void notifyIFRecvd(addr_t pc);
  void notifyIFFetch(addr_t pc);
  void notifyDecode(addr_t pc, unsigned char op);
  void notifyLSReq(addr_t a);
  void notifyLSResp(addr_t a);
  void notifyFlush();
  void notifyMemXBar(bool is_write, uint64_t last_time, uint32_t time_usage);
  void notifyCacheResp(addr_t addr, bool is_hit, uint16_t id);
  void notifyCacheReq(addr_t addr, uint16_t id);
  void notifyBrOutcome(bool pred_taken, bool actual_taken,
                       uint32_t pred_target, uint32_t actual_target,
                       bool btb_hit, uint32_t br_pc);
  void notifyCommit(addr_t pc, unsigned char stalltp);
  void reset_stats();

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
