#include "pmemacc.hh"
#include "dpic.hh"

#include <cassert>
#include <chrono>
#include <cstdint>

#include <cstdio>
#include <ctime>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <ostream>
#include <ratio>
#include <sys/types.h>
#include <utility>
#include <verilated.h>
// #define PRINTF_COND 1

using addr_t = uint32_t;
const char PMemFile[] = "/home/kong/ysyx-workbench/npc/rvproc/prog-rom/meminit.bin";
constexpr size_t PMemSize{ 0x1000'0000U }; // 32 MiB
static uint32_t pmem_raw[PMemSize >> 2];

constexpr addr_t BaseAddr{ 0x8000'0000U };
constexpr auto ValidAccess = [](size_t idx) -> bool {
  return idx < (PMemSize >> 2);
};

template<typename... Args>
inline void v_assert(bool cond, const Args&... args) {
  if (!cond) {
    std::cerr << "[ASSERT FAILED] " << __FILE__ << ":" << __LINE__ << " " << std::hex;
    ((std::cerr << args << " "), ...);
    std::cerr << std::endl;
    // std::abort();
    vl_fatal(__FILE__, __LINE__, "BlackBox", "assertion failed");
  }
}

namespace rv_device {
  constexpr addr_t SerialAddr{ 0x1000'0000U };
  constexpr addr_t ClockAddr{ 0x1000'0020U };

  bool is_mem_range(addr_t a) {
    return a >= BaseAddr;
  }
  bool is_clock_range(addr_t a) {
    return a >= ClockAddr && a < ClockAddr + 8U;
  }
  bool is_serial_range(addr_t a) {
    return a == SerialAddr;
  }

  void write_serial(unsigned char ch) {
#ifdef PRINTF_COND
    std::cout << "WRITE SERIAL !! \'" << ch << "\'" << std::endl; 
#endif
    putchar(ch);
  }

  inline std::chrono::microseconds 
  get_uptime() {
    using std::chrono::duration_cast;
    using std::chrono::seconds;
    using std::chrono::milliseconds;
    using std::chrono::nanoseconds;
    std::timespec ts;
    clock_gettime(CLOCK_BOOTTIME, &ts);
    
    return duration_cast<milliseconds>(
      seconds(ts.tv_sec) + nanoseconds(ts.tv_nsec));
  }

  uint32_t read_clock(bool hi) {
    return static_cast<uint64_t>(get_uptime().count()) >> 
      (hi ? 32ULL : 0ULL);
//     std::ifstream file("/proc/uptime");
//     assert(file.is_open());
//     double uptime_seconds = 0.0;
//     assert(file >> uptime_seconds && "Read uptime failed");
//
//     auto seconds_duration = std::chrono::duration<double>(uptime_seconds);
//     auto micro_duration = std::chrono::duration_cast<std::chrono::microseconds>(seconds_duration);
//     auto micro_i64 = static_cast<uint64_t>(micro_duration.count());
// #ifdef PRINTF_COND
//     std::cout << std::endl << "READ CLOCK !! \'" << micro_i64 << "\'" << std::endl; 
// #endif // PRINTF_COND
//     return static_cast<uint32_t>(micro_i64 >> (hi ? 32 : 0));
  }
}

extern "C" void 
pmem_init() {
  // ccdb::pmem_init_hello();
#if PRINTF_COND
  std::cout << "DPI-C >> pmem_init called" << std::endl;
#endif
  std::ifstream ifs(PMemFile, std::ios::binary | std::ios::in);
  assert(ifs.is_open());

  ifs.seekg(0, std::ios::end);
  auto const file_size = ifs.tellg();
#if PRINTF_COND
  std::cout << "DPI-C >> file size " << std::dec << file_size << std::endl;
#endif
  assert(file_size != std::ifstream::pos_type(-1));
  ifs.seekg(0, std::ios::beg);

  ifs.read((char *) pmem_raw, file_size);
#if PRINTF_COND
  std::cout << "DPI-C >> fail " << ifs.fail() << " eof " << ifs.eof() << std::endl;
#endif
  assert(!ifs.fail());

  // for (size_t i = 0x0; i < 0x20; i++) {
  //   if (i % 4 == 0) {
  //     std::cout << std::hex << i << ":\t";
  //   }
  //   std::cout << std::hex << std::setfill('0') << std::setw(8) << pmem_raw[i] << " ";
  //   if (i % 4 == 3) {
  //     std::cout << std::endl;
  //   }
  // }
}

// pmem_init() {
// #if PRINTF_COND
//   std::cout << "DPI-C >> pmem_init called" << std::endl;
// #endif
//   std::ifstream ifs (PMemFile);
//   assert(ifs.is_open());
//
//   std::string rline{};
//   size_t pos{ 0U };
//   while (std::getline(ifs, rline)) {
//     assert (pos < (PMemSize >> 2) && "Mem init out of bound");
//     std::stringstream ss {rline};
//     ss >> std::hex >> pmem_raw[pos++];
//   }
//   ifs.close();
// }

extern "C" uint32_t 
pmem_read(uint32_t raddr) {
#if PRINTF_COND
  std::cout << "DPI-C >> pmem_read addr " << std::hex << raddr << std::endl;
#endif
  uint32_t ret = 0;
  if (raddr == 0) { ret = 0; }
  else if (rv_device::is_clock_range(raddr)) {
    ret = rv_device::read_clock(raddr != rv_device::ClockAddr);
  } else {
    // Memory
    uint32_t aln_idx = (raddr - BaseAddr) >> 2;
    v_assert(ValidAccess(aln_idx), std::string("Read addr = "), raddr);
    ret = pmem_raw[aln_idx];
  }
#if PRINTF_COND
  std::cout << " ret = " << std::hex << ret << std::endl;
#endif
  ccdb::pmem_access(raddr, false, ret, 0xf);
  return ret;
}

extern "C" void
pmem_write(uint32_t waddr, uint32_t wdata, uint8_t wmask) {
  if (rv_device::is_serial_range(waddr)) {
    v_assert((wmask & 0x1), "Serial write masked out, wmask = ", wmask);
    rv_device::write_serial(wdata & 0xff);
    // TODO: Device trace here
  } else {
    uint32_t aln_idx = (waddr - BaseAddr) >> 2;
    v_assert(ValidAccess(aln_idx), std::string("Write addr = "), waddr);
    uint32_t m = 0U;
    for (int i = 0; i < 4; i++) {
      if (wmask & (1 << i)) {
        m |= (0xff << (i * 8));
      }
    }
    pmem_raw[aln_idx] = 
      (pmem_raw[aln_idx] & ~m) | (wdata & m);
#if PRINTF_COND
    std::cout << "DPI-C >> pmem_write addr" << std::hex << waddr << " : " << wdata << " mask = " << m << std::endl;
    for (size_t i = 0x100 >> 2; i < (0x100+20) >> 2; i++) {
      if (i % 4 == 0) {
        std::cout << std::hex << i << ":\t";
      }
      std::cout << std::hex << pmem_raw[i] << " ";
      if (i % 4 == 3) {
        std::cout << std::endl;
      }
    }
#endif
    ccdb::pmem_access(waddr, false, wdata, wmask);
  }
}

std::pair<bool, uint32_t>
dpic::pmem_probe(uint32_t addr) {
  uint32_t aln_idx = (addr - BaseAddr) >> 2;
  bool valid = ValidAccess(aln_idx);
  uint32_t ret = 0;
  if (valid) {
    ret = pmem_raw[aln_idx];
  }
  return std::make_pair(valid, ret);
}
