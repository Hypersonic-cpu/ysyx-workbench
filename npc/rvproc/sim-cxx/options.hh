#pragma once

#include <cassert>
#include <cstdlib>
#include <ctime>
#include <getopt.h>
#include <string>
// #include <unordered_map>

namespace options {

struct DumpPrintOpt {
  bool mem_buf = true;
  bool frame_stk = true;
  bool inst_buf = true;
  bool reg_file = true;
  bool elf_symbol = true;
  bool cycle_no = false;
};
constexpr bool wave_enable{LOGENA};
constexpr bool diff_enable{DIFFENA};
constexpr bool gdbg_enable{DBGENA};

extern std::string outdir;
extern bool record_perf;

enum ArchConfig {
  // ICacheSize = 256,
  // ICacheAssoc,
  // ICacheBlock,
  SDRAMSize,
  IssueNum,
};

// Handle by components
// extern std::unordered_map<ArchConfig, std::string> arch_config_name;
// extern std::unordered_map<ArchConfig, size_t> arch_config_val;

extern std::string binary_img;
extern DumpPrintOpt runtime_dump_opt;
extern DumpPrintOpt error_dump_opt;
extern std::string wave_file;
extern std::string elf_file;
extern bool fast;
extern size_t max_cycles;

void parse_args(int argc, char* argv[]);

} // namespace options
