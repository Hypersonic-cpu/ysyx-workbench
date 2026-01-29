#include "runtime.hh"
#include "difftest.hh"
#include "options.hh"
#include "pmu.hh"
#include "probe.hh"
#include "types.hh"
#include <cassert>
#include <cstdint>
#include <future>
#include <unistd.h>

#if SOCMODE

const RuntimeBin* mrom = nullptr;
const RuntimeBin* flash = nullptr;
RuntimeBin* psram = nullptr;
RuntimeBin* sdram = nullptr;
RuntimeBin* vmem = nullptr;

void
mrom_read(int32_t addr, int32_t* data) {
  *(uint32_t*)data = mrom->readWord(addr);
}

void
flash_read(int32_t addr, int32_t* data) {
  *(uint32_t*)data = flash->readWord(addr);
  // std::cerr << std::hex;
  // std::cerr << "DPI-C flash read @ " << addr << " data = " << *data
  //           << std::endl;
}

uint8_t
psram_read(uint32_t addr) {
  // std::cerr << std::hex;
  // std::cerr << "DPI-C psram read @ " << addr << " data = " <<
  // psram->readByte(addr)
  //           << std::endl;
  return psram->readByte(addr);
}

void
psram_write(uint32_t addr, unsigned char data) {
  // std::cerr << std::hex;
  // std::cerr << "DPI-C psram write @ " << addr << " data = " << (uint16_t)
  // data
  //           << std::endl;
  psram->writeByte(addr, data);
}

uint16_t
sdram_read(uint32_t addr) {
  // std::cerr << std::hex;
  // std::cerr << "DPI-C sdram read @ " << addr
  //           << " data = " << sdram->readHalf(addr) << std::endl;
  // assert(sdram && "De-ref nullptr");
  return sdram->readHalf(addr);
}

void
sdram_write(uint32_t addr, unsigned short data, unsigned char mask) {
  // std::cerr << std::hex;
  // std::cerr << "DPI-C sdram write @ " << addr << " data = " << data
  //           << " mask = " << (uint16_t)mask << std::endl;
  // assert(sdram && "De-ref nullptr");
  sdram->writeHalf(addr, data, mask);
}

void
vga_write(uint32_t addr, uint32_t data, unsigned char strb) {
  // std::cerr << std::hex;
  // std::cerr << "DPI-C vga write @ " << addr << " data = " << data
  //           << " mask = " << (uint16_t)strb << std::endl;
  // assert(vmem && "De-ref nullptr");
  vmem->writeWord(addr, data, strb);
}

uint32_t
vga_read(uint32_t addr) {
  // std::cerr << std::hex;
  // std::cerr << "DPI-C vga read @ " << addr
  //           << " data = " << vmem->readWord(addr) << std::endl;
  // assert(vmem && "De-ref nullptr");
  return vmem->readWord(addr);
}

#else

RuntimeBin* unifiedMem = nullptr;
cacheSim::CacheSimulator* iCache = nullptr;

// mt-unsafe
tick_t
pmem_read(uint32_t araddr, uint32_t* prdata, bool bfirst, uint16_t) {
  static uint64_t ready_time = 0;

  auto curr_lat = bfirst ? MemLatency : MemBstLat;
  auto finish_time = std::max(ready_time, curr_tick()) + curr_lat;
  if (ppmu) {
    ppmu->notifyMemXBar(false, ready_time, curr_lat);
  }
  ready_time = finish_time;

  assert(unifiedMem);
  *prdata = unifiedMem->readWord(araddr & ~3U);
  return finish_time;
}

tick_t
pmem_write(uint32_t awaddr, uint32_t wdata, unsigned char wstrb,
           bool bfirst, uint16_t) {
  static uint64_t ready_time = 0;

  auto curr_lat = bfirst ? MemLatency : MemBstLat;
  auto finish_time = std::max(ready_time, curr_tick()) + curr_lat;
  if (ppmu) {
    ppmu->notifyMemXBar(true, ready_time, curr_lat);
  }
  ready_time = finish_time;

  if (awaddr == 0x1000'0000) [[unlikely]] {
    putchar(wdata);
    goto rettime;
  }
  assert(unifiedMem);
  unifiedMem->writeWord(awaddr & ~3U, wdata, wstrb);
rettime:
  return finish_time;
}

uint32_t
axi_read(uint32_t araddr, uint32_t* prdata, uint16_t id) {
  // std::cerr << std::hex;
  // std::cerr << "DPI-C axi read [" << id << "]@ " << araddr
  //           << " data = " << unifiedMem->readWord(araddr) << std::endl;
  assert(unifiedMem);
  auto finish_time = 0;
  if (iCache && id == 0) {
    finish_time = iCache->read_req(araddr, prdata);
  } else {
    finish_time = pmem_read(araddr, prdata, true, 0xff);
  }
  v_assert(finish_time > curr_tick(), "Finished", finish_time, "< curr",
           curr_tick());
  tint_t latency = finish_time - curr_tick();
  v_warn_dec(latency < 20'000U, "Too large read latency", latency);
  return latency;
}

uint32_t
axi_write(uint32_t awaddr, uint32_t wdata, unsigned char wstrb,
          uint16_t id) {
  assert(unifiedMem);
  auto finish_time = 0;
  if (iCache && id == 0) {
    finish_time = iCache->write_req(awaddr, wdata, wstrb);
  } else {
 finish_time = pmem_write(awaddr, wdata, wstrb, true, 0xff);
  }
  v_assert(finish_time > curr_tick(), "Finished", finish_time, "< curr",
           curr_tick());
  tint_t latency = finish_time - curr_tick();
  v_warn_dec(latency < 20'000U, "Too large write latency", latency);
  return latency;
}

void
axi_cache_flush(uint16_t id) {
  std::cerr << std::hex;
  std::cerr << "DPI-C cache flush [" << id << "]" << std::endl;
  if (iCache && id == 0) {
    iCache->flush_all();
  }
}

#endif

trace::GuestTracer* pccdb = nullptr;
trace::SoftPerfUnit* ppmu = nullptr;
trace::DiffTester* pdiff = nullptr;

void
notify_recvd(uint32_t pc, uint32_t inst) {
  ppmu->notifyIFRecvd(pc);
}

void
notify_fetch(uint32_t pc) {
  ppmu->notifyIFFetch(pc);
}

void
notify_ls_req(uint32_t a) {
  ppmu->notifyLSReq(a);
}

void
notify_ls_resp(uint32_t a) {
  ppmu->notifyLSResp(a);
}

void
notify_decode(uint32_t pc, unsigned char iop) {
  ppmu->notifyDecode(pc, iop);
}

void
notify_flush() {
  ppmu->notifyFlush();
}

void
notify_commit(uint32_t pc, uint32_t inst, unsigned char stalltp) {
  ppmu->notifyCommit(pc, stalltp);
  if (stalltp != 0)
    return;
  pccdb->inst_trace(pc, inst);
  if constexpr (options::diff_enable) {
    pdiff->upd_dut_pc(pc);
    pdiff->setFire();
  }
}
