#pragma once
#include <algorithm>
#include <cstdint>
#include <iomanip>
#include <iostream>
#include <array>
#include <vector>

/** WARN:
 *  该文件禁止引用 ccdb.{cc,hh} 和 pmemacc.{cc,hh}, 
 *  而只能被他们引用. 
 */
namespace comm {
  inline std::ostream& 
  sout32(std::ostream& os, std::string prefix="0x") {
    os << prefix << std::setfill('0') << std::setw(8) << std::hex;
    os << std::setfill(' ');
    return os;
  }

  class InstEnt {
    public:
      uint32_t const pc;
      uint32_t const inst;
      std::string const disasm;
      void printent(std::ostream& os) const {
        comm::sout32(os) << pc << " : ";
        comm::sout32(os, "") << inst << " \t" << disasm;
        os << std::endl;
      }
  };

  class MemEnt {
    public:
      uint32_t const addr;
      bool const is_write;
      uint32_t const value;
      // Otherwise ostream<< will treat it as char.
      uint16_t addr_mask;
      void printent(std::ostream& os) const {
        os << (is_write ? "Write" : "Read ");
        comm::sout32(os, " @ 0x") << addr << " : ";
        comm::sout32(os, "") << value;
        if (is_write) { os << " mask " << std::hex << std::setw(1) << addr_mask; }
        os << std::endl;
      }
  };
  
  template<class T, std::size_t N>
  class RingBuffer {
    public:
      RingBuffer() : ptr{ 0U }, buf{} {}
      const T& append(const T& t) {
        buf.at(ptr).~T();
        new (&buf.at(ptr)) T(t);
        auto const& ret = buf.at(ptr);
        ptr = (ptr + 1) % N;
        return ret;
      }

      T const atmod(size_t idx) const {
        return buf.at(idx % N);
      }

      T& atmod(size_t idx) {
        return buf.at(idx % N);
      }

      void printbuf(std::ostream& os, const std::string& title) const {
        os << "\n === " << title << " === " << std::endl;
        for (size_t i = 0; i < N; i++) {
          atmod(i).printent(os);
        }
      }

      size_t size() const { return N; }

    protected:
      size_t ptr;
      std::array<T, N> buf;
  };

  /** Global var */
  extern RingBuffer<InstEnt, 16> instBuf;
  extern RingBuffer<MemEnt, 16> memBuf;

  void mem_acc_log(
      uint32_t addr, bool is_write, uint32_t data, uint8_t byte_mask);

  class ElfSymEnt {
    public:
      const std::string name;
      const uint32_t addr;
      const uint32_t size;
  };

  extern std::vector<ElfSymEnt> elf_syms;
}

// NOTE: 这是main用于窥探dpic SV 的namespace.
// DPI-C 选择暴露这些接口. 定义应该在 pememacc.cc.
namespace dpic {
  std::pair<bool, uint32_t> pmem_probe(uint32_t addr);
}
