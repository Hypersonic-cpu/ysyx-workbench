#pragma once

#include "probe.hh"

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <elf.h>
#include <fcntl.h>
#include <capstone/capstone.h>
#include <cassert>
#include <cstdlib>
#include <dlfcn.h>
#include <format>
#include <iostream>
#include <iterator>
#include <ostream>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <vector>
#include <vector>

namespace trace {

template<typename T>
class DistriBase {
  protected:
  enum MetaIdx {
    Overflow = 0, Underflow, Min, Max, Num_MetaIdx
  };
  inline static const std::vector<std::string> MetaName{
    "Overflow", "Underflow", "Min", "Max" };
  enum StatIdx {
    Sum = 0, Avg, Num_StatIdx
  };
  inline static const std::vector<std::string> StatName{
    "Sum", "Avg" };

  T min;
  T max;
  T delta;
  T maxidx;
  std::vector<size_t> arr;
  std::vector<size_t> meta;
  std::vector<double> stat;
  size_t total;
  const std::string name;

  public:
  DistriBase(T min, T max, T delta, const std::string& name)
  : min{ min }
  , max{ max }
  , delta{ delta }
  , maxidx{ (max-min) / delta }
  , arr(maxidx, 0U)
  , meta(Num_MetaIdx, 0U)
  , stat(Num_StatIdx, 0.0)
  , total{ 0 }
  , name{ name }
  {}

  virtual void sample(T v, size_t n=1) {
    total += n;
    if (v >= max) { meta.at(Overflow) += n; }
    else if (v < min) { arr.at(Underflow) += n; }
    else {
      auto idx = (v - min) / delta;
      arr.at(idx) += n;
    }
    meta.at(Max) = std::max<T>(v, meta.at(Max));
    meta.at(Min) = std::min<T>(v, meta.at(Min));
    stat.at(Sum) += static_cast<double>(v);
    auto frac = static_cast<double>(total-n) / total;
    stat.at(Avg) = frac * stat.at(Avg) + (1.0 - frac) * v;
  }

  std::vector<size_t>
  get_stats() const {
    auto ret = arr;
    for (auto const& elem: meta) {
      ret.emplace_back(elem);
    }
    for (auto const& elem: stat) {
      ret.emplace_back(elem);
    }
    return std::move(ret);
  }

  std::vector<std::string>
  get_indices() const {
    std::vector<std::string> ret(maxidx);
    for (auto i = 0U; i < maxidx; i++) {
      ret.at(i) = std::to_string(min + delta * i);
    }
    for (auto const& elem: MetaName) {
      ret.emplace_back(elem);
    }
    for (auto const& elem: StatName) {
      ret.emplace_back(elem);
    }
    return std::move(ret);
  }

  size_t
  get_total() const { return total; }

  double
  get_avg() const { return stat.at(Avg); }

  double
  get_sum() const { return stat.at(Sum); }

  void
  dump_stats(std::ostream& os=std::cout) const {
    os << this->name << std::endl;
    os << std::format("{:8s} : {}",
      "samples",
      get_total()
    ) << std::endl;
    for (auto i = 0U; i < maxidx; i++) {
      os << std::format("{:8s} : {}",
        std::to_string(min + delta * i),
        arr.at(i)
      ) << std::endl;
    }
    for (auto i = 0U; i < MetaName.size(); i++) {
      os << std::format("{:8s} : {}",
        MetaName.at(i), meta.at(i)
      ) << std::endl;
    }
    for (auto i = 0U; i < StatName.size(); i++) {
      os << std::format("{:8s} : {:f}",
        StatName.at(i), stat.at(i)
      ) << std::endl;
    }
    os << std::endl;
  }

  void
  dump_brief(std::ostream& os) const {
    os << std::format("{:10s} : samples {:12d} , avg {:f}",
      this->name, get_total(), get_avg()
    ) << std::endl;
  }
};

template<typename T>
class DistriDelta : public DistriBase<T> {
  private:
  T last;
  public:
  DistriDelta(T min, T max, T delta, T init, const std::string& name)
  : DistriBase<T>(min, max, delta, name)
  , last{ init }
    {}

    void sample(T v, size_t n=1) override {
      auto dpc = v - last;
      DistriBase<T>::sample(dpc, n);
      last = v;
    }

    void updlast(T v) {
      last = v;
    }
};

template <typename U, typename T>
requires IsDerived<U, DistriBase<T>>
class DistriVec {
  protected:
  std::vector<U> cats;
  size_t sumtotal;
  const std::string name;
  public :
  DistriVec(size_t n, T min, T max, T delta, T init,
    const std::string& name,
    const std::vector<std::string>& names)
  : sumtotal{ 0 }
  , name{ name }
  , cats {}
  {
    assert(n == names.size());
    for (auto i = 0U; i < n; i++) {
      auto const nm = names.size() ? names.at(i) : ("Cat_" + std::to_string(i));
      if constexpr (std::is_same<U, DistriDelta<T>>::value) {
        cats.emplace_back(min, max, delta, init, nm);
      } else {
        cats.emplace_back(min, max, delta, nm);
      }
    }
  }

  void sample(size_t cat, T value) {
    sumtotal++;
    cats.at(cat).sample(value);
  }

  size_t size() const { return cats.size(); }
  size_t total() const { return sumtotal; }
  const U& operator[] (size_t idx) const {
    return cats[idx];
  }

  void dump_stats(std::ostream& os) const {
    os << std::format("{} total samples {:d}", this->name, sumtotal) << std::endl;
    for (const auto& c : cats) {
      c.dump_brief(os);
    }
    os << std::endl;
  }
};

class SoftPerfUnit {
  public:
  inline static const std::unordered_map<unsigned char, size_t>
    InstOpToIdx
  {
    { 0b00000U, 0 } ,
    { 0b00100U, 1 } ,
    { 0b00101U, 2 } ,
    { 0b01000U, 3 } ,
    { 0b01100U, 4 } ,
    { 0b10100U, 5 } ,
    { 0b01101U, 6 } ,
    { 0b11000U, 7 } ,
    { 0b11001U, 8 } ,
    { 0b11011U, 9 } ,
    { 0b11100U, 10 }
  };

  inline static const std::vector<std::string>
    InstOpName =
    { "Load", "OpImm", "Auipc", "Store", "OpReg",
      "OpFP", "Lui", "Branch", "Jalr", "Jal", "System" };

  private:
  // DeltaDistri<int64_t> pcjmp;
  DistriDelta<uint64_t> ifcyc;
  DistriDelta<uint64_t> lscyc;
  DistriVec<DistriBase<uint64_t>, uint64_t> instcyc;

  // FIXME: FIFO 在 pipeline 的情况下是对的
  // 不需要分inst类型统计含IF 的周期...
  using iboard_t = std::tuple<addr_t, size_t, uint64_t>;
  std::list<iboard_t> instboard;

  public:
  SoftPerfUnit()
  : instboard{}
  , ifcyc(0, 30, 2, std::numeric_limits<int64_t>::max(), "Inst Fetch Cycles")
  , lscyc(0, 30, 2, std::numeric_limits<int64_t>::max(), "Load Store Cycles")
  , instcyc(InstOpName.size(), 0, 30, 2,
      std::numeric_limits<int64_t>::max(),
      "Inst Cats",
      InstOpName)
  {}

  void dump_stats(std::ostream& os=std::cout) const {
    ifcyc.dump_stats(os);
    lscyc.dump_stats(os);
    instcyc.dump_stats(os);
  }

  void notifyIFIssue(addr_t pc) {
    ifcyc.sample(curr_tick());
    // pcjmp.sample(static_cast<int64_t>(pc));
    // fprintf(stderr, "sample at %x\n", read_double_csr(MCycleh, MCycle));
  }
  void notifyIFFetch(addr_t pc) {
    ifcyc.updlast(curr_tick());
    // fprintf(stderr, "updlast at %x\n", read_double_csr(MCycleh, MCycle));
  }
  void notifyLSReq(addr_t a) {
    // fprintf(stderr, "req at %x\n", read_double_csr(MCycleh, MCycle));
    lscyc.updlast(curr_tick());
  }
  void notifyLSResp(addr_t a) {
    // fprintf(stderr, "resp at %x\n", read_double_csr(MCycleh, MCycle));
    lscyc.sample(curr_tick());
  }
  void notifyDecode(addr_t pc, unsigned char op) {
    instboard.emplace_back(pc, InstOpToIdx.at(op), curr_tick());
  }
  void notifyCommit(addr_t pc) {
    auto it = std::find_if(instboard.begin(), instboard.end(),
      [&pc](const iboard_t& ib) { return std::get<0>(ib) == pc; });
    v_assert(it != instboard.end(), "Cannot find pc", pc, "in inst board");
    auto const [pc_, tp, t0] = *it;
    auto const deltat = curr_tick() - t0;
    instcyc.sample(tp, deltat);
    instboard.erase(it);
  }
};

} // namespace trace
