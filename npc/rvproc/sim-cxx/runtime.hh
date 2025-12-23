#pragma once
#include <cstdint>
#include <fstream>
#include <ios>
#include <iostream>
#include <string>
#include <vector>

#include "probe.hh"

extern "C" void flash_read(int32_t addr, int32_t* data);

extern "C" void mrom_read(int32_t addr, int32_t* data);

extern "C" uint8_t psram_read(uint32_t addr);

extern "C" void psram_write(uint32_t addr, unsigned char data);

class RuntimeBin;
extern const RuntimeBin* mrom;
extern const RuntimeBin* flash;
extern RuntimeBin* psram;

class RuntimeBin {
private:
  std::string name;
  addr_t baseAddr;
  std::vector<ureg_t> data;

  static inline bool
  isAligned(addr_t addr) {
    return (addr & 0b11) == 0;
  }

public:
  RuntimeBin() = delete;
  RuntimeBin(const RuntimeBin&) = delete;
  RuntimeBin& operator=(const RuntimeBin&) = delete;
  RuntimeBin(const std::string& bin, addr_t base, const std::string& name)
      : data{}, baseAddr{base}, name{name} {
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
    v_assert(!!fileStream, "Binary of MROM read failed");
    fileStream.close();
  }
  RuntimeBin(const std::vector<ureg_t>& vec, addr_t base,
             const std::string& name)
      : data(vec), baseAddr{base}, name{name} {}

  ureg_t
  readAligned(addr_t addr) const {
    // v_assert(isAligned(addr), "Unaligned read @", addr);
    auto idx = (addr - baseAddr) >> 2;
    v_assert(idx < data.size(), "Out of bound read of", name, " @ ", addr);
    return data.at(idx);
  }

  uint8_t
  readByte(addr_t addr) const {
    auto idx = (addr - baseAddr) >> 2;
    if (idx == data.size()) [[unlikely]] {
      return 0b11000011U;
    }
    v_assert(idx < data.size(), "Out of bound read of", name, " @ ", addr);
    auto shamt = (addr % 4) * 8;
    return 0xffU & (data[idx] >> shamt);
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

  const std::vector<ureg_t>&
  dataVec() const {
    return data;
  }
};
