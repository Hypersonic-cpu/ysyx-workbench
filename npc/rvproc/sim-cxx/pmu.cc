#include "pmu.hh"

#include <algorithm>
#include <format>
#include <iostream>

namespace trace {

SoftPerfUnit::SoftPerfUnit()
    : instboard{}
    , ifetchboard{}
    , flushedRec{false, 0}
    , ifcyc(0, 100, 10, "Inst Fetch Cycles")
    , lscyc(0, 100, 10, std::numeric_limits<int64_t>::max(),
            "Load Store Cycles")
    , instcyc(InstOpName.size(), 0, 100, 10,
              std::numeric_limits<int64_t>::max(), "Inst Cats", InstOpName)
    , recoverTime(0, 60, 3, "BrRecover Time")
    , instStatus("InstBreakdown", InstBreakdownName)
    , cycStatus("BlockedCause", CycBreakdownName)
    , memRdStatus("XBarReadUsage", XBarBreakdownName)
    , memWrStatus("XBarWriteUsage", XBarBreakdownName)
    , cacheRates("L1ICache", CacheBreakdownName)
    , dcacheRates("L1DCache", CacheBreakdownName)
    , bpStats("BranchPred", BpBreakdownName) {}

void
SoftPerfUnit::dump_stats(std::ostream& os) const {
  os << std::format("Cycles {:d}\n  InstRet {:d} IPC {:.6f} StallCyc {:d}",
                    get_cycles(), get_instret(), get_ipc(),
                    get_cycles() - get_instret())
     << std::endl;
  for (auto const& ptr : statslist) {
    ptr->dump_stats(os);
  }
  if (cacheRates.get_samples() > 0) {
    os << std::format("iCache Miss Rate : {:.2f}%",
                      100.0 * cacheRates.at(CacheBreakdown::Miss) / cacheRates.get_samples())
       << std::endl;
  }
  if (dcacheRates.get_samples() > 0) {
    os << std::format("dCache Miss Rate : {:.2f}%",
                      100.0 * dcacheRates.at(CacheBreakdown::Miss) / dcacheRates.get_samples())
       << std::endl;
  }
  auto bp_total = bpStats.get_samples();
  if (bp_total > 0) {
    auto bp_correct = bpStats.at(BpCorrect);
    os << std::format("BrPred Accuracy : {:.2f}% ({:d}/{:d})",
                      100.0 * bp_correct / bp_total, bp_correct, bp_total)
       << std::endl;
    std::vector<std::pair<uint32_t, uint32_t>> topWrong;
    for (auto& [pc, s] : bpPerPC)
      if (s.wrong > 0)
        topWrong.push_back({pc, s.wrong});
    std::sort(topWrong.begin(), topWrong.end(),
              [](auto& a, auto& b) { return a.second > b.second; });
    // os << "Top mispredicting PCs:" << std::endl;
    // for (size_t i = 0;
    //      i < std::min(topWrong.size(), size_t(15)); i++) {
    //   auto pc = topWrong[i].first;
    //   auto& s = bpPerPC[pc];
    //   os << std::format(
    //        "  {:08x} wrong {:6d} correct {:6d} "
    //        "total {:6d} acc {:.1f}%",
    //        pc, s.wrong, s.correct, s.wrong + s.correct,
    //        100.0 * s.correct / (s.wrong + s.correct))
    //      << std::endl;
    // }
  }
  if (pfIssued > 0) {
    uint64_t pfUnused = pfIssued > pfUseful ?
      pfIssued - pfUseful : 0;
    os << std::format(
          "Prefetch Issued {:d} Hit {:d} Useful {:d}"
          " Unused {:d}",
          pfIssued, pfHitC2, pfUseful, pfUnused)
       << std::endl;
  }
}

json
SoftPerfUnit::stats_json() const {
  json ret{};
  for (auto const& ptr : statslist) {
    ret[ptr->name()] = ptr->gen_json();
  }
  ret["ipc"] = get_ipc();
  if (pfIssued > 0) {
    ret["pf.issued"]  = pfIssued;
    ret["pf.hitC2"]   = pfHitC2;
    ret["pf.useful"]  = pfUseful;
    ret["pf.unused"]  = pfIssued > pfUseful ?
      pfIssued - pfUseful : 0;
  }
  return ret;
}

#if DBGENA
void
SoftPerfUnit::notifyIFRecvd(addr_t pc) {
  ifRingPush('R', pc, curr_tick());
  // if (ifetchboard.empty() || std::get<0>(ifetchboard.front()) != pc) {
  //   ifRingDump();
  // }
  v_assert(!ifetchboard.empty(), "Cannot find pc @", pc, "in IF Pipeline");
  while (std::get<0>(ifetchboard.front()) != pc) {
    ifetchboard.pop_front();
    // if (ifetchboard.empty()) {
    //   ifRingDump();
    // }
    v_assert(!ifetchboard.empty(), "Cannot find pc @", pc, "in IF Pipeline");
  }
  ifcyc.sample(curr_tick() - std::get<1>(ifetchboard.front()));
  ifetchboard.pop_front();
}

void
SoftPerfUnit::notifyIFFetch(addr_t pc) {
  ifRingPush('F', pc, curr_tick());
  ifetchboard.emplace_back(pc, curr_tick());
}

void
SoftPerfUnit::notifyDecode(addr_t pc, unsigned char op) {
  instboard.emplace_back(pc, InstOpToIdx.at(op), curr_tick());
  if (flushedRec.first) {
    recoverTime.sample(curr_tick() - flushedRec.second);
    flushedRec.first = false;
  }
}

void
SoftPerfUnit::notifyLSReq(addr_t a) {
  lscyc.updlast(curr_tick());
}

void
SoftPerfUnit::notifyLSResp(addr_t a) {
  lscyc.sample(curr_tick());
}

void
SoftPerfUnit::notifyFlush() {
  flushedRec = {true, curr_tick()};
  instboard.clear();
}

void
SoftPerfUnit::notifyMemXBar(bool is_write, uint64_t last_time,
                            uint32_t time_usage) {
  auto curr_time = curr_tick();
  auto& sel = is_write ? memWrStatus : memRdStatus;
  if (last_time < curr_time) {
    sel.sample(XBarBreakdown::XBarIdle, curr_time - last_time);
  } else if (last_time >= curr_time) {
    sel.sample(XBarBreakdown::XBarBlocking, last_time - curr_time);
  }
  sel.sample(XBarBreakdown::XBarServing, time_usage);
}

void
SoftPerfUnit::notifyCacheResp(addr_t addr, bool is_hit, uint16_t id) {
  if (id == 1) {
    if (is_hit)
      dcacheRates.sample(CacheBreakdown::Hit);
    else
      dcacheRates.sample(CacheBreakdown::Miss);
    return;
  }
  auto& front = icacheboard.front();
  auto [f_addr, f_tick] = front;
  v_assert(f_addr == addr, "Cache access queue front", f_addr,
           "!= resp. addr", addr);
  if (is_hit) {
    cacheRates.sample(CacheBreakdown::Hit);
  } else {
    cacheRates.sample(CacheBreakdown::Miss);
  }
  icacheboard.pop_front();
}

void
SoftPerfUnit::notifyCacheReq(addr_t addr, uint16_t id) {
  if (id == 1)
    return;
  icacheboard.emplace_back(addr, curr_tick());
}

void
SoftPerfUnit::notifyPfEvent(uint8_t event_type, addr_t addr) {
  switch (event_type) {
  case 0: ++pfIssued; break;
  case 1: ++pfHitC2; break;
  case 2: ++pfUseful; break;
  default: break;
  }
}

void
SoftPerfUnit::notifyBrOutcome(bool pred_taken, bool actual_taken,
                              uint32_t pred_target, uint32_t actual_target,
                              bool btb_hit, uint32_t br_pc) {
  bool is_correct;
  if (!btb_hit && actual_taken) {
    bpStats.sample(BpBtbMiss);
    is_correct = false;
  } else if (pred_taken != actual_taken) {
    bpStats.sample(BpWrongDir);
    is_correct = false;
  } else if (pred_taken && actual_taken && pred_target != actual_target) {
    bpStats.sample(BpWrongTarget);
    is_correct = false;
  } else {
    bpStats.sample(BpCorrect);
    is_correct = true;
  }
  auto& s = bpPerPC[br_pc];
  if (is_correct)
    s.correct++;
  else
    s.wrong++;
}

void
SoftPerfUnit::notifyCommit(addr_t pc, unsigned char stalltp) {
  auto cause = static_cast<CycBreakdown>(stalltp);
  cycStatus.sample(cause);
  if (cause != CycBreakdown::NoStall)
    return;

  auto it = std::find_if(
    instboard.begin(), instboard.end(),
    [&pc](const iboard_t& ib) { return std::get<0>(ib) == pc; });
  if (it != instboard.end()) {
    auto const [pc_, tp, t0] = *it;
    auto const deltat = curr_tick() - t0;
    instcyc.sample(tp, deltat);
    instboard.erase(it);
  }

  instStatus.sample(Commit, 1);
}

#else
void
SoftPerfUnit::notifyIFRecvd(addr_t pc) {}
void
SoftPerfUnit::notifyIFFetch(addr_t pc) {}
void
SoftPerfUnit::notifyDecode(addr_t pc, unsigned char op) {}
void
SoftPerfUnit::notifyLSReq(addr_t a) {}
void
SoftPerfUnit::notifyLSResp(addr_t a) {}
void
SoftPerfUnit::notifyFlush() {}
void
SoftPerfUnit::notifyMemXBar(bool is_write, uint64_t last_time,
                            uint32_t time_usage) {}
void
SoftPerfUnit::notifyCacheResp(addr_t addr, bool is_hit, uint16_t id) {}
void
SoftPerfUnit::notifyCacheReq(addr_t addr, uint16_t id) {}
void
SoftPerfUnit::notifyPfEvent(uint8_t event_type, addr_t addr) {}
void
SoftPerfUnit::notifyBrOutcome(bool pred_taken, bool actual_taken,
                              uint32_t pred_target, uint32_t actual_target,
                              bool btb_hit, uint32_t br_pc) {}
void
SoftPerfUnit::notifyCommit(addr_t pc, unsigned char stalltp) {
  auto cause = static_cast<CycBreakdown>(stalltp);
  cycStatus.sample(cause);
  if (cause != CycBreakdown::NoStall)
    return;
  instStatus.sample(Commit, 1);
}
#endif // DBGENA

void
SoftPerfUnit::reset_stats() {
  for (auto const& ptr : statslist) {
    ptr->reset_stats();
  }
  bpPerPC.clear();
  pfIssued = 0;
  pfHitC2 = 0;
  pfUseful = 0;
}

void
SoftPerfUnit::ifRingDump() const {
  std::cerr << "=== IFetch Event Ring ===\n";
  size_t start = ifRingIdx > kIFRingSz ? ifRingIdx - kIFRingSz : 0;
  for (size_t i = start; i < ifRingIdx; ++i) {
    auto& e = ifRing[i % kIFRingSz];
    std::cerr << std::format("  [{}] {} pc={:08x} tick={}\n", i, e.type,
                             e.pc, e.tick);
  }
  std::cerr << "=== ifetchboard contents ===\n";
  for (auto& [a, t] : ifetchboard) {
    std::cerr << std::format("  pc={:08x} tick={}\n", a, t);
  }
  std::cerr << std::flush;
}

} // namespace trace
