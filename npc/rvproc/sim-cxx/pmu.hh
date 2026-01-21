#pragma once

#include "options.hh"
#include "probe.hh"

#include <algorithm>
#include <capstone/capstone.h>
#include <cassert>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <format>
#include <iostream>
#include <ostream>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unordered_map>
#include <utility>
#include <vector>

namespace trace {

class StatsBase {
protected:
  const std::string name_;

public:
  StatsBase(const std::string& name)
      : name_{name} {}
  StatsBase() = delete;

  std::string
  name() const {
    return name_;
  }

  virtual json gen_json() const = 0;
  virtual void dump_stats(std::ostream& os = std::cout) const = 0;
  virtual void reset_stats() = 0;
};

template <typename U> class ClassifiedStats : public StatsBase {
protected:
  std::unordered_map<std::string, size_t> arr;
  const std::unordered_map<U, std::string> idToNames;
  size_t samples;

public:
  ClassifiedStats(const std::string& name,
                  const std::unordered_map<U, std::string>& nameMap)
      : StatsBase(name)
      , arr{}
      , idToNames(nameMap)
      , samples{0} {
    reset_stats();
  }

  auto
  sample(U bucket, size_t num = 1) -> void {
    samples += num;
    auto const& nm = idToNames.at(bucket);
    arr[nm] += num;
  }

  json
  gen_json() const override {
    return json(arr);
  }

  void
  dump_stats(std::ostream& os) const override {
    os << std::format("{:10s} : samples {:d} ", this->name_, samples);
    for (auto const& [id, nm] : idToNames) {
      os << std::format(", {:s} {:d} ", nm, arr.at(nm));
    }
    os << std::endl;
  }

  void
  reset_stats() override {
    samples = 0;
    for (auto const& [id, nm] : idToNames) {
      arr[nm] = 0;
    }
  }

  size_t
  get_samples() const {
    return samples;
  }

  size_t
  at(U key) const {
    return arr.at(idToNames.at(key));
  }
};

template <typename T> class DistriBase : public StatsBase {
protected:
  enum MetaIdx { Overflow = 0, Underflow, Min, Max, Num_MetaIdx };
  inline static const std::vector<std::string> MetaName{
    "Overflow", "Underflow", "Min", "Max"};
  enum StatIdx { Sum = 0, Avg, Num_StatIdx };
  inline static const std::vector<std::string> StatName{"Sum", "Avg"};

  T min;
  T max;
  T delta;
  T maxidx;
  std::vector<size_t> arr;
  std::vector<size_t> meta;
  std::vector<double> stat;
  size_t samples;

public:
  DistriBase(T min, T max, T delta, const std::string& name)
      : StatsBase(name)
      , min{min}
      , max{max}
      , delta{delta}
      , maxidx{(max - min) / delta}
      , arr(maxidx, 0U)
      , meta(Num_MetaIdx, 0U)
      , stat(Num_StatIdx, 0.0)
      , samples{0} {}

  virtual void
  sample(T v, size_t n = 1) {
    samples += n;
    if (v >= max) {
      meta.at(Overflow) += n;
    } else if (v < min) {
      arr.at(Underflow) += n;
    } else {
      auto idx = (v - min) / delta;
      arr.at(idx) += n;
    }
    meta.at(Max) = std::max<T>(v, meta.at(Max));
    meta.at(Min) = std::min<T>(v, meta.at(Min));
    stat.at(Sum) += static_cast<double>(v);
    auto frac = static_cast<double>(samples - n) / samples;
    stat.at(Avg) = frac * stat.at(Avg) + (1.0 - frac) * v;
  }

  virtual void
  reset_stats() override {
    arr.assign(maxidx, 0U);
    meta.assign(Num_MetaIdx, 0U);
    stat.assign(Num_StatIdx, 0.0);
    samples = 0;
  }

protected:
  template <typename R>
  static auto
  gen_zip(const std::vector<std::string>& nm, const std::vector<R>& val) {
    assert(nm.size() == val.size() && "Inconsist length in zip");
    std::vector<std::pair<std::string, R>> ret{};
    for (size_t i = 0; i < nm.size(); i++) {
      ret.emplace_back(nm.at(i), val.at(i));
    }
    return std::unordered_map<std::string, R>(ret.begin(), ret.end());
  }

public:
  json
  gen_json() const override {
    std::vector<std::string> buckets{};
    for (auto i = 0U; i < maxidx; i++) {
      buckets.emplace_back(std::to_string(min + delta * i));
    }
    json ret = gen_zip(buckets, arr);
    ret.update(json(gen_zip(StatName, stat)));
    ret.update(json(gen_zip(MetaName, meta)));
    ret["samples"] = samples;
    return ret;
  }

  size_t
  get_samples() const {
    return samples;
  }

  double
  get_avg() const {
    return stat.at(Avg);
  }

  double
  get_sum() const {
    return stat.at(Sum);
  }

  void
  dump_stats(std::ostream& os) const override {
    os << std::format("{:10s} : samples {:10d} , avg {:f}", this->name_,
                      get_samples(), get_avg())
       << std::endl;
  }
};

template <typename T> class DistriDelta : public DistriBase<T> {
private:
  T last;

public:
  DistriDelta(T min, T max, T delta, T init, const std::string& name)
      : DistriBase<T>(min, max, delta, name)
      , last{init} {}

  void
  sample(T v, size_t n = 1) override {
    auto dpc = v - last;
    DistriBase<T>::sample(dpc, n);
    last = v;
  }

  void
  updlast(T v) {
    last = v;
  }
};

template <typename U, typename T>
  requires IsDerived<U, DistriBase<T>>
class DistriVec : public StatsBase {
protected:
  std::vector<U> cats;
  size_t sumsamples;

public:
  DistriVec(size_t n, T min, T max, T delta, T init, const std::string& name,
            const std::vector<std::string>& names)
      : StatsBase(name)
      , sumsamples{0}
      , cats{} {
    assert(n == names.size());
    for (auto i = 0U; i < n; i++) {
      auto const nm =
        names.size() ? names.at(i) : ("Cat_" + std::to_string(i));
      if constexpr (std::is_same<U, DistriDelta<T>>::value) {
        cats.emplace_back(min, max, delta, init, nm);
      } else {
        cats.emplace_back(min, max, delta, nm);
      }
    }
  }

  void
  reset_stats() override {
    for (auto& c : cats) {
      c.reset_stats();
    }
    sumsamples = 0;
  }

  void
  sample(size_t cat, T value) {
    sumsamples++;
    cats.at(cat).sample(value);
  }

  size_t
  size() const {
    return cats.size();
  }

  size_t
  samples() const {
    return sumsamples;
  }

  const U&
  operator[](size_t idx) const {
    return cats[idx];
  }

  json
  gen_json() const override {
    json ret{};
    for (auto const& c : cats) {
      ret[c.name()] = c.gen_json();
    }
    ret["samples"] = samples();
    return ret;
  }

  void
  dump_stats(std::ostream& os) const override {
    os << std::format("{} samples {:d}", this->name(), sumsamples)
       << std::endl;
    return;
    for (const auto& c : cats) {
      c.dump_stats(os);
    }
    os << std::endl;
  }
};

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

private:
  DistriDelta<int64_t> pcjmp;
  DistriBase<uint64_t> ifcyc;
  DistriDelta<uint64_t> lscyc;
  DistriVec<DistriBase<uint64_t>, uint64_t> instcyc;

  ClassifiedStats<CycBreakdown> cycStatus;
  ClassifiedStats<InstBreakdown> instStatus;

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
      , cycStatus("BlockedCause", CycBreakdownName) {}

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
  notifyIFIssue(addr_t pc) {
    auto head = ifetchboard.front();
    v_assert(std::get<0>(head) == pc, "IF pipeline trace fail, expect",
             std::get<0>(head), "got", pc);
    ifcyc.sample(curr_tick() - std::get<1>(head));
    ifetchboard.pop_front();

    // using namespace std;
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
