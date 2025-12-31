#include "runtime.hh"
#include "pmu.hh"
#include <cassert>
#include <cstdint>
#include <memory>

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

uint32_t
pmem_read(uint32_t araddr, uint32_t* prdata, bool bfirst) {
  // std::cerr << std::hex;
  // std::cerr << "DPI-C axi read @ " << araddr
  //           << " data = " << unifiedMem->readWord(araddr) << std::endl;
  assert(unifiedMem);
  *prdata = unifiedMem->readWord(araddr & ~3U);
  return MemLatency;
}

uint32_t
pmem_write(uint32_t awaddr, uint32_t wdata, unsigned char wstrb,
           bool bfirst) {
  assert(unifiedMem);
  if (awaddr == 0x1000'0000) [[unlikely]] {
    putchar(wdata);
    goto rettime;
  }
  unifiedMem->writeWord(awaddr & ~3U, wdata, wstrb);
rettime:
  return MemLatency;
}

uint32_t
axi_read(uint32_t araddr, uint32_t* prdata, uint16_t id) {
  // std::cerr << std::hex;
  // std::cerr << "DPI-C axi read @ " << araddr
  //           << " data = " << unifiedMem->readWord(araddr) << std::endl;
  assert(unifiedMem);
  if (iCache && id == 0) {
    return iCache->read_req(araddr, prdata);
  }
  *prdata = unifiedMem->readWord(araddr & ~3U);
  return MemLatency;
}

uint32_t
axi_write(uint32_t awaddr, uint32_t wdata, unsigned char wstrb,
          uint16_t id) {
  assert(unifiedMem);
  if (awaddr == 0x1000'0000) [[unlikely]] {
    putchar(wdata);
    return MemLatency;
  }
  if (iCache && id == 0) {
    return iCache->write_req(awaddr, wdata, wstrb);
  }
  unifiedMem->writeWord(awaddr & ~3U, wdata, wstrb);
  return MemLatency;
}

#endif

trace::GuestTracer* pccdb = nullptr;
trace::SoftPerfUnit* ppmu = nullptr;

void
notify_issue(uint32_t pc) {
  ppmu->notifyIFIssue(pc);
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
notify_decode(uint32_t pc, unsigned char itype, unsigned char iop) {
  ppmu->notifyDecode(pc, iop);
}

void
notify_commit(uint32_t pc) {
  ppmu->notifyCommit(pc);
}
