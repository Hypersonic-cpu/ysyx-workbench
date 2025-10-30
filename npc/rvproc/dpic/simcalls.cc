#include <ios>
#include <iostream>
#include <iomanip>
#include <verilated.h>

extern "C" void 
call_ebreak(uint32_t pc, uint32_t a10reg) {
  std::cout << "Hit " << (a10reg ? "GOOD" : "BAD") 
    << " trap at pc = " << std::hex << pc 
    << " with a10 = " << std::hex << a10reg << std::endl;
  Verilated::gotFinish(true);
}
