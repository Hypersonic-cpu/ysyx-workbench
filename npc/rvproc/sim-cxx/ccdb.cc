#include "ccdb.hh"

#include <cstdint>
#include <utility>

std::pair<bool, uint32_t>
ccdb::read_reg(ptop_t top, uint8_t regid) {
  return ccdb::_read_verilator_reg(top, regid);
}
