#pragma once
#include "rtl_defs.hh"

#include <cassert>
#include <cstdint>
#include <fstream>
#include <ios>
#include <iostream>
#include <string>
#include <vector>

#include "ccdb.hh"
#include "difftest.hh"
#include "pmu.hh"

extern "C" void call_ebreak(uint32_t pc, uint32_t a0, uint32_t a5);

#if SOCMODE

extern "C" void flash_read(int32_t addr, int32_t* data);
extern "C" void mrom_read(int32_t addr, int32_t* data);

extern "C" uint8_t psram_read(uint32_t addr);
extern "C" void psram_write(uint32_t addr, unsigned char data);

extern "C" uint16_t sdram_read(uint32_t addr);
extern "C" void sdram_write(uint32_t addr, uint16_t data,
                            unsigned char mask);

extern "C" void vga_write(uint32_t addr, uint32_t data, unsigned char strb);
extern "C" uint32_t vga_read(uint32_t addr);

#else

constexpr tint_t MemLatency{40U};
constexpr tint_t MemBstLat{8U};
extern "C" tint_t axi_read(addr_t, ureg_t*, bool);
extern "C" tint_t axi_write(addr_t, ureg_t, uint8_t, bool);

extern "C" void pmem_read(addr_t, ureg_t*);
extern "C" void pmem_write(addr_t, ureg_t, uint8_t);

#endif

extern "C" {
void notify_recvd(uint32_t pc, uint32_t inst);
void notify_fetch(uint32_t pc);
void notify_ls_req(uint32_t addr);
void notify_ls_resp(uint32_t addr);
void notify_decode(uint32_t pc, unsigned char iop);
void notify_commit(uint32_t pc, uint32_t inst, unsigned char stalltp);
void notify_flush();

void notify_cache_resp(addr_t a, uint8_t is_hit, uint16_t id);
void notify_cache_req(addr_t a, uint16_t id);

void notify_bp_outcome(uint8_t pred_taken, uint8_t actual_taken,
                       uint32_t pred_target, uint32_t actual_target,
                       uint8_t btb_hit, uint32_t br_pc);
}

class RuntimeBin;

#if SOCMODE
extern const RuntimeBin* mrom;
extern const RuntimeBin* flash;
extern RuntimeBin* psram;
extern RuntimeBin* sdram;
extern RuntimeBin* vmem;
#else
extern RuntimeBin* unifiedMem;
#endif

extern trace::GuestTracer* pccdb;
extern trace::SoftPerfUnit* ppmu;
extern trace::DiffTester* pdiff;

class RuntimeBin {
private:
  std::string name;
  addr_t baseAddr;
  std::vector<ureg_t> data;

  static inline bool
  isAligned(addr_t addr) {
    return (addr & 0b11) == 0;
  }

  ureg_t
  readAny(addr_t addr, uint8_t len) const {
    auto idx = (addr - baseAddr) >> 2;
    if (idx >= data.size()) [[unlikely]] {
      return 0xbadc0deU;
    }
    v_assert(idx < data.size(), "Out of bound read of", name, " @ ", addr);
    v_assert(addr % len == 0, "Unaligned read @", addr, "len",
             (uint16_t)len);
    auto shamt = (addr % 4) * 8;
    return data[idx] >> shamt;
  }

  void
  writeAny(addr_t addr, ureg_t wdata, uint8_t strb) {
    uint32_t idx = (addr - baseAddr) >> 2;
    v_assert(idx < data.size(), "Out of bound write of", name, " @ ", addr);
    v_assert(addr % 2 == 0, "Unaligned write @", addr, "len 2");
    uint32_t mask32 = 0;
    if (strb & 1)
      mask32 |= 0x0000'00ffLLU;
    if (strb & 2)
      mask32 |= 0x0000'ff00LLU;
    if (strb & 4)
      mask32 |= 0x00ff'0000LLU;
    if (strb & 8)
      mask32 |= 0xff00'0000LLU;
    data[idx] &= ~mask32;
    data[idx] |= wdata;
  }

public:
  RuntimeBin() = delete;
  RuntimeBin(const RuntimeBin&) = delete;
  RuntimeBin& operator=(const RuntimeBin&) = delete;
  RuntimeBin(const std::string& bin, addr_t base, const std::string& name)
      : data{}
      , baseAddr{base}
      , name{name} {
    std::ifstream fileStream;
    v_assert(isAligned(base), "Unaligned base addr", base);
    fileStream.open(bin, std::ios::binary | std::ios::in | std::ios::ate);
    if (!fileStream.is_open()) {
      throw std::runtime_error("Open failed: " + bin);
    }
    size_t fileSize = fileStream.tellg();
    fileStream.seekg(0, std::ios::beg);

    data.resize((fileSize + 3) / 4);
    fileStream.read(reinterpret_cast<char*>(data.data()), fileSize);
    v_assert(!!fileStream, "Binary read failed");
    fileStream.close();
  }

  RuntimeBin(const std::string& bin, size_t veclen, addr_t base,
             const std::string& name)
      : RuntimeBin(bin, base, name) {
    assert(veclen >= data.size());
    data.resize(veclen);
  }

  RuntimeBin(const std::vector<ureg_t>& vec, addr_t base,
             const std::string& name)
      : data(vec)
      , baseAddr{base}
      , name{name} {}

  ureg_t
  readWord(addr_t addr) const {
    return readAny(addr, 4);
  }

  uint8_t
  readByte(addr_t addr) const {
    return readAny(addr, 1) & 0xffU;
  }

  uint16_t
  readHalf(addr_t addr) const {
    return readAny(addr, 2) & 0xffffU;
  }

  void
  writeByte(addr_t addr, uint8_t wdata) {
    auto idx = (addr - baseAddr) >> 2;
    // std::cerr << std::dec << idx << " <-> " << data.size() << std::endl;
    v_assert(idx < data.size(), "Out of bound write of", name, " @ ", addr);
    auto shamt = (addr % 4) * 8;
    auto mask32 = 0xffU << shamt;
    data[idx] &= ~mask32;
    data[idx] |= static_cast<uint32_t>(wdata) << shamt;
  }

  void
  writeHalf(addr_t addr, uint16_t wdata, uint8_t bena) {
    auto idx = (addr - baseAddr) >> 2;
    v_assert(idx < data.size(), "Out of bound write of", name, " @ ", addr);
    v_assert(addr % 2 == 0, "Unaligned write @", addr, "len 2");
    auto shamt = (addr % 4) * 8;
    uint32_t mask32 = 0;
    if (bena & 1)
      mask32 |= 0xffU;
    if (bena & 2)
      mask32 |= 0xff00U;
    mask32 <<= shamt;
    data[idx] &= ~mask32;
    data[idx] |= static_cast<uint32_t>(wdata) << shamt;
  }

  void
  writeWord(addr_t addr, ureg_t wdata, uint8_t strb) {
    auto shamt = addr % 4;
    writeAny(addr, wdata << (shamt * 8), strb << shamt);
  }

  const std::vector<ureg_t>&
  dataVec() const {
    return data;
  }
};
