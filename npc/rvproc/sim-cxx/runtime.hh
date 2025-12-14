#pragma once
#include <cstdint>
#include <fstream>
#include <ios>
#include <iostream>
#include <memory>
#include <string>
#include <vector>

#include "probe.hh"

extern "C" void flash_read(int32_t addr, int32_t* data);

extern "C" void mrom_read(int32_t addr, int32_t* data);

class RuntimeBin;
extern const RuntimeBin* mrom;

class RuntimeBin {
protected:
  addr_t baseAddr;
  size_t fileSize;
  std::vector<ureg_t> data;

  inline bool
  isAligned(addr_t addr) const {
    return (addr & 0b11) == 0;
  }

public:
  RuntimeBin() = delete;
  RuntimeBin(const RuntimeBin&) = delete;
  RuntimeBin& operator=(const RuntimeBin&) = delete;
  RuntimeBin(const std::string& bin, addr_t base) : data{}, baseAddr{base} {
    std::ifstream fileStream;
    v_assert(isAligned(base), "Unaligned base addr", base);
    fileStream.open(bin, std::ios::binary | std::ios::in | std::ios::ate);
    if (!fileStream.is_open()) {
      throw std::runtime_error("Open failed: " + bin);
    }
    fileSize = fileStream.tellg();
    fileStream.seekg(0, std::ios::beg);

    data.resize((fileSize + 3) / 4);
    fileStream.read(reinterpret_cast<char*>(data.data()), fileSize);
    v_assert(!!fileStream, "Binary of MROM read failed");
    fileStream.close();
  }
  RuntimeBin(const char* bin, addr_t base)
      : RuntimeBin(std::string{bin}, base) {}

  ureg_t
  readAligned(addr_t addr) const {
    v_assert(isAligned(addr), "Unaligned read @", addr);
    return data.at((addr - baseAddr) >> 2);
  }
};
