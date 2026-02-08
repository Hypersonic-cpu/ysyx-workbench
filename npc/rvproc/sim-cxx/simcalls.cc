#include "rtl_defs.hh"
#include "runtime.hh"

#include <cassert>
#include <cstdlib>
#include <format>
#include <ios>
#include <iostream>
#include <verilated.h>

void
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
