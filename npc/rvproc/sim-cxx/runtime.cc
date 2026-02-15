#include "difftest.hh"
#include "options.hh"
#include "pmu.hh"
#include "probe.hh"
#include "runtime.hh"
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
uint32_t
pmem_read(uint32_t araddr, uint32_t* prdata, bool bfirst) {
  static uint64_t ready_time = 0;
  // TODO: Exact number
  auto curr_lat = bfirst ? MemLatency : MemBstLat;
  auto finish_time = std::max(ready_time, curr_tick()) + curr_lat;
  if (ppmu) {
    ppmu->notifyMemXBar(false, ready_time, curr_lat);
  }
  ready_time = finish_time;
  assert(unifiedMem);
  *prdata = unifiedMem->readWord(araddr & ~3U);
  return finish_time - curr_tick();
}

uint32_t
pmem_write(uint32_t awaddr, uint32_t wdata, unsigned char wstrb,
           bool bfirst) {
  static uint64_t ready_time = 0;
  // TODO: Exact number
  auto curr_lat = bfirst ? MemLatency : MemBstLat;
  auto finish_time = std::max(ready_time, curr_tick()) + curr_lat;
  if (ppmu) {
    ppmu->notifyMemXBar(true, ready_time, curr_lat);
  }
  ready_time = finish_time;
  // TODO: upd ready time
  if (awaddr == 0x1000'0000) [[unlikely]] {
    putchar(wdata);
    goto rettime;
  }
  assert(unifiedMem);
  unifiedMem->writeWord(awaddr & ~3U, wdata, wstrb);
rettime:
  return finish_time - curr_tick();
}

uint32_t
axi_read(uint32_t araddr, uint32_t* prdata, uint16_t id) {
  // std::cerr << std::hex;
  // std::cerr << "DPI-C axi read [" << id << "]@ " << araddr
  //           << " data = " << unifiedMem->readWord(araddr) << std::endl;
  assert(unifiedMem);
  if (iCache && id == 0) {
    return iCache->read_req(araddr, prdata);
  }
  return pmem_read(araddr, prdata, true);
}

uint32_t
axi_write(uint32_t awaddr, uint32_t wdata, unsigned char wstrb,
          uint16_t id) {
  assert(unifiedMem);
  if (iCache && id == 0) {
    return iCache->write_req(awaddr, wdata, wstrb);
  }
  return pmem_write(awaddr, wdata, wstrb, true);
}

void
axi_cache_flush(uint16_t id) {
  std::cerr << std::hex;
  std::cerr << "DPI-C cache flush [" << id << "]" << std::endl;
  if (iCache && id == 0) {
    // TODO: 记得清空流水线
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
