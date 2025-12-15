#include "ccdb.hh"
#include "probe.hh"

namespace trace {

void
elftable_dump(const ebuf_t& elfSyms, std::ostream& os) {
  os << "\n === ELF Funct Symbols (" << elfSyms.size() << " total) === ";
  os << std::endl;
  for (auto const& [addr, ent] : elfSyms) {
    util::sout32(os) << addr;
    os << " size " << std::dec << std::setfill(' ') << std::setw(6)
       << ent.size;
    os << " : " << ent.name << std::endl;
  }
}

void
inst_dump(const ibuf_t& instBuf, std::ostream& os) {
  os << "\n=== Inst Ring Buffer === " << std::endl;
  for (size_t i = 0; i < instBuf.size(); i++) {
    instBuf.atidx(i).printent(os);
  }
}

void
frame_dump(const fbuf_t& frameStk, std::ostream& os) {
  os << "\n=== Frame Stack === " << std::endl;
  for (const auto& ent : frameStk) {
    ent.printent(os);
  }
}

void
regfile_dump(std::ostream& os) {
  using util::sout32;
  os << "\n=== Register File === " << std::endl;
  for (size_t i = 0; i < RegNum + 1; ++i) {
    os << std::setfill(' ') << "[";
    if (i == RegNum) {
      os << "  ";
    } else {
      os << std::dec << std::setw(2) << i;
    }
    os << "] " << RegName.at(i) << " : ";
    sout32(os) << read_reg(i) << std::endl;
  }
  for (size_t i = 0; i < csr_list.size(); ++i) {
    os << std::setfill(' ') << "CSRs " << std::setw(10) << RegName.at(i)
       << " : ";
    sout32(os) << read_reg(i) << std::endl;
  }
}

void
membuf_dump(const mbuf_t& memBuf, std::ostream& os) {
  os << "\n=== Mem Ring Buffer === " << std::endl;
  for (size_t i = 0; i < memBuf.size(); i++) {
    memBuf.atidx(i).printent(os);
  }
}


} // namespace trace
