#include "runtime.hh"
#include "difftest.hh"
#include "options.hh"
#include "pmu.hh"
#include "rtl_defs.hh"
#include <cassert>
#include <cstdint>
#include <format>
#include <iostream>
#include <unistd.h>
#include <verilated.h>

extern "C" void
call_ebreak(uint32_t pc, uint32_t a0, uint32_t a5) {
  if (a5 == 0) {
    std::cout << std::format(ANSI_YELLOW "reset stats @ pc {:8x}" ANSI_NONE,
                             pc)
              << std::endl;
    resetAllStats();
  } else if (a5 == 1) {
    std::cout << std::format(ANSI_YELLOW "dump Stats @ pc {:>8x}" ANSI_NONE,
                             pc)
              << std::endl;
    dumpAllStats();
  } else {
    std::cout << (a0 ? (ANSI_B_RED "Hit BAD trap" ANSI_NONE)
                     : (ANSI_B_GREEN "Hit GOOD trap" ANSI_NONE))
              << " at pc = 0x" << std::hex << pc << " with a0 = 0x"
              << std::hex << a0 << std::endl;
    if (a0 == 0) {
      vl_finish(__FILE__, __LINE__, "EcallBox:call_ebreak");
    } else {
      abortHandler();
      // throw std::runtime_error("EcallBox: hit bad trap");
    }
  }
}

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

// PMemBox DPI-C stubs - PMemBox.sv is copied into build-sv
// unconditionally but not instantiated in SoC mode.
tint_t
axi_read(addr_t, ureg_t* prdata, bool) {
  *prdata = 0;
  return 0;
}

tint_t
axi_write(addr_t, ureg_t, uint8_t, bool) {
  return 0;
}

#else

RuntimeBin* unifiedMem = nullptr;

void
pmem_read(addr_t araddr, ureg_t* prdata) {
  if (ppmu) {
    // TODO: Time
    ppmu->notifyMemXBar(false, 0, 0);
  }
  *prdata = unifiedMem->readWord(araddr & ~3U);
}

// Always functional
void
pmem_write(addr_t awaddr, ureg_t wdata, uint8_t wstrb) {
  // TODO: Handle burst in one call
  static uint64_t ready_time = 0;
  if (ppmu) {
    ppmu->notifyMemXBar(true, 0, 0);
  }

  if (awaddr == SerialAddr) [[unlikely]] {
    putchar(wdata);
  } else {
    unifiedMem->writeWord(awaddr & ~3U, wdata, wstrb);
  }
}

tint_t
axi_read(addr_t araddr, ureg_t* prdata, bool outstanding) {
  // std::cerr << std::hex;
  // std::cerr << "DPI-C axi read [" << id << "]@ " << araddr
  //           << " data = " << unifiedMem->readWord(araddr) << std::endl;
  // static uint64_t ready_time = 0;
  // auto curr_lat = bfirst ? MemLatency : MemBstLat;
  // auto finish_time = std::max(ready_time, curr_tick()) + curr_lat;
  // if (ppmu) {
  //   ppmu->notifyMemXBar(false, ready_time, curr_lat);
  // }
  // ready_time = finish_time;
  // assert(unifiedMem);
  // *prdata = unifiedMem->readWord(araddr & ~3U);
  // return finish_time - curr_tick();
  pmem_read(araddr, prdata);
  // std::cerr << std::format("DPI-C AXI READ @{:x}[{:s}] Data {:08x}
  // T@{:d}", araddr,
  //                          outstanding ? "First" : "Burst", *prdata,
  //                          curr_tick())
  //           << std::endl;
  auto lat = outstanding ? MemLatency : MemBstLat;
  return lat;
}

tint_t
axi_write(addr_t awaddr, ureg_t wdata, uint8_t wstrb, bool outstanding) {
  // static uint64_t ready_time = 0;
  //   auto curr_lat = bfirst ? MemLatency : MemBstLat;
  //   auto finish_time = std::max(ready_time, curr_tick()) + curr_lat;
  //   if (ppmu) {
  //     ppmu->notifyMemXBar(true, ready_time, curr_lat);
  //   }
  //   ready_time = finish_time;
  //   if (awaddr == 0x1000'0000) [[unlikely]] {
  //     putchar(wdata);
  //     goto rettime;
  //   }
  //   assert(unifiedMem);
  //   unifiedMem->writeWord(awaddr & ~3U, wdata, wstrb);
  // rettime:
  //   return finish_time - curr_tick();
  pmem_write(awaddr, wdata, wstrb);
  auto lat = outstanding ? MemLatency : MemBstLat;
  return lat;
}

#endif

trace::GuestTracer* pccdb = nullptr;
trace::SoftPerfUnit* ppmu = nullptr;
trace::DiffTester* pdiff = nullptr;

static uint32_t last_recvd_pc = 0;
static uint32_t last_recvd_inst = 0;
void
notify_recvd(uint32_t pc, uint32_t inst) {
  last_recvd_pc = pc;
  last_recvd_inst = inst;
  ppmu->notifyIFRecvd(pc);
}

static uint32_t last_fetch_pc = 0;
static int fetch_count = 0;
void
notify_fetch(uint32_t pc) {
  fetch_count++;
  if (pc == 0) {
    std::cerr << "FATAL: PC=0 fetch #" << std::dec << fetch_count
              << ", last_fetch=0x" << std::hex << last_fetch_pc
              << ", last_recvd=0x" << last_recvd_pc
              << " inst=0x" << last_recvd_inst << std::endl;
    abortHandler();
  }
  last_fetch_pc = pc;
  ppmu->notifyIFFetch(pc);
}

void
notify_ls_req(uint32_t a) {
  ppmu->notifyLSReq(a);
#if DIFFENA
  if ((a >= 0x1000'0000 && a < 0x1000'1000)
      || (a >= 0x0200'0000 && a < 0x0201'0000)) {
    pdiff->setDeviceAccess();
  }
#endif
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
  pdiff->checkSkipMatch(inst);
  pdiff->updateDutPC(pc);
  pdiff->setFire();
}

void
notify_cache_resp(addr_t addr, uint8_t is_hit, uint16_t id) {
  ppmu->notifyCacheResp(addr, is_hit, id);
}

void
notify_cache_req(addr_t addr, uint16_t id) {
  ppmu->notifyCacheReq(addr, id);
}

void
notify_pf_event(uint8_t event_type, addr_t addr) {
  ppmu->notifyPfEvent(event_type, addr);
}

void
notify_bp_outcome(uint8_t pred_taken, uint8_t actual_taken,
                  uint32_t pred_target, uint32_t actual_target,
                  uint8_t btb_hit, uint32_t br_pc) {
  ppmu->notifyBrOutcome(pred_taken, actual_taken, pred_target, actual_target,
                        btb_hit, br_pc);
}
