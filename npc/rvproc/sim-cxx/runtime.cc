#include "runtime.hh"
#include "cacheSim/CacheBase.hh"
#include "rtl_defs.hh"

#include "difftest.hh"
#include "options.hh"
#include "pmu.hh"
#include "probe.hh"
#include <cassert>
#include <cstdint>
#include <format>
#include <future>
#include <unistd.h>

#ifdef DPICDBG
#include <iostream>
#define DPICERR(fmt, ...)                                                   \
  do {                                                                      \
    std::cerr << std::format("{:d}: " fmt, curr_tick(), ##__VA_ARGS__)      \
              << std::endl;                                                 \
  } while (0)
#else
#define DPICERR(fmt, ...)
#endif

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
  // std::cerr << std::hex;runtime.cc
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
cacheSim::CacheBase* iCache = nullptr;
cacheSim::CacheBase* dCache = nullptr;

// Update before sim loop
std::array<CacheSimRespBuffer, 2> cacheRespBuf;

// mt-unsafe
tick_t
pmem_read(uint32_t araddr, uint32_t* prdata) {
  if (ppmu) {
    ppmu->notifyMemXBar(false, -2, -2);
  }
  *prdata = unifiedMem->readWord(araddr & ~3U);
  std::cerr << std::format("PMEM READ @ {:8x} Data {:8x}\n", araddr,
                           *prdata);
  return 0; // unused
}

tick_t
pmem_write(uint32_t awaddr, uint32_t wdata, unsigned char wstrb) {
  if (ppmu) {
    ppmu->notifyMemXBar(true, -2, -2);
  }

  if (awaddr == 0x1000'0000) [[unlikely]] {
    putchar(wdata);
    goto rettime;
  }
  unifiedMem->writeWord(awaddr & ~3U, wdata, wstrb);
rettime:
  return 0;
}

inline cacheSim::CacheBase*
sel_port_by_id(uint16_t id) {
  switch (id) {
  case 0:
    return iCache;
  case 1:
    return dCache;
  default:
    v_assert(false, "No such ID", id, "for i|dCache");
  };
  return nullptr;
}

void
axi_read_req(addr_t addr, uint16_t id, uint16_t len, uint16_t size,
             uint16_t burst) {
  DPICERR("DPI-C read req @ {:08x} ID = {:d}", addr, id);
  assert(len == 0);
  sel_port_by_id(id)->read_req(addr);
}

void
axi_write_req(addr_t addr, uint16_t id, uint16_t len, uint16_t size,
              uint16_t burst, word_t data, uint8_t strb, uint8_t last) {
  assert(len == 0);
  sel_port_by_id(id)->write_req(addr, data, strb);
}

void
axi_cache_flush(uint16_t id) {
  // std::cerr << std::hex;
  // std::cerr << "DPI-C cache flush [" << id << "]" << std::endl;
  sel_port_by_id(id)->flush_all();
}

void
axi_read_resp(uint8_t* pvalid, uint8_t* presp, word_t* pdata, uint8_t* plast,
              uint16_t* pid, uint16_t devid, uint8_t devready) {
  auto& ent = cacheRespBuf.at(devid);
  *pvalid = ent.r_valid;
  *presp = static_cast<uint8_t>(ent.r_resp);
  *pdata = ent.r_data;
  *plast = ent.r_last;
  *pid = devid;
  DPICERR("DPI-C read resp (before), ID = {:d} Va:Re {:d}:{:d}", devid,
          ent.r_valid, devready);
  if (devready)
    ent.r_valid = false;
  DPICERR("DPI-C read resp (after), ID = {:d} Va:Re {:d}:{:d}", devid,
          ent.r_valid, devready);
}

void
axi_write_resp(uint8_t* pvalid, uint8_t* presp, uint16_t* pid,
               uint16_t devid, uint8_t devready) {
  auto& ent = cacheRespBuf.at(devid);
  *pvalid = ent.b_valid;
  *presp = static_cast<uint8_t>(ent.b_resp);
  *pid = devid;
  if (devready)
    ent.b_valid = false;
}

void
axi_device_ready(uint8_t* pr, uint8_t* pw, uint16_t devid) {
  auto* ptr = sel_port_by_id(devid);
  auto const [rr, wr] = ptr->is_ready();
  // DPICERR("DPI-C read probe, ID = {:d} Ready {:d}:{:d}", devid, rr, wr);
  *pr = rr;
  *pw = wr;
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
