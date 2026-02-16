#include "options.hh"
#include "probe.hh"

#include <csignal>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <filesystem>
#include <format>
#include <getopt.h>
#include <ios>
#include <iostream>
#include <string>
// #include <unordered_map>

namespace options {

std::string outdir = "";
bool record_perf = false;
std::string binary_img = "";
DumpPrintOpt runtime_dump_opt{false, false, false, false, false};
DumpPrintOpt error_dump_opt{false, true, true, true, false, false};
std::string wave_file = "";
std::string elf_file = "";
bool fast = false;
size_t max_cycles = ~0ULL;

// std::unordered_map<ArchConfig, size_t> arch_config_val{
//   {ICacheSize, 1024}, {ICacheAssoc, 1}, {ICacheBlock, 16}};

static size_t
parse_size(std::string s) {
  std::transform(s.begin(), s.end(), s.begin(),
                 [](unsigned char c) { return std::tolower(c); });
  size_t multiplier = 1;
  size_t last_num_idx = s.find_last_of("0123456789");

  if (last_num_idx == std::string::npos)
    throw std::format_error("Emtpy size string");

  std::string unit = s.substr(last_num_idx + 1);
  if (unit == "b" || unit == "") {
    multiplier = 1;
  } else if (unit == "kb" || unit == "k") {
    multiplier = 1024ULL;
  } else if (unit == "mb" || unit == "m") {
    multiplier = 1024ULL * 1024ULL;
  } else if (unit == "gb" || unit == "g") {
    multiplier = 1024ULL * 1024ULL * 1024ULL;
  }

  try {
    size_t number = std::stoull(s.substr(0, last_num_idx + 1));
    return number * multiplier;
  } catch (...) {
    throw std::format_error("cannot parse size " + s);
    return 0;
  }
}

void
parse_args(int argc, char* argv[]) {
  constexpr struct option table[] = {
    {"print-mem", no_argument, NULL, 'm'},
    {"print-inst", no_argument, NULL, 'i'},
    {"print-dev", no_argument, NULL, 'd'},
    {"print-frame", no_argument, NULL, 'f'},
    {"print-cycle", no_argument, NULL, 'c'},
    {"fast-mode", no_argument, NULL, 'F'},
    {"max-cycle", required_argument, NULL, 'M'},
    {"outdir", required_argument, NULL, 'O'},
    {"record-perf", no_argument, NULL, 'R'},
    {"log", required_argument, NULL, 'l'},
    {"elf", required_argument, NULL, 'e'},
    {"help", no_argument, NULL, 'h'},
    // {"l1i-size", required_argument, NULL, ArchConfig::ICacheSize},
    // {"l1i-blksize", required_argument, NULL, ArchConfig::ICacheBlock},
    // {"l1i-assoc", required_argument, NULL, ArchConfig::ICacheAssoc},
    {0, 0, NULL, 0},
  };

  std::string custom_dir = "";
  { /** Default dir */
    auto now = std::chrono::system_clock::now();
    auto in_time_t = std::chrono::system_clock::to_time_t(now);
    std::stringstream ss;
    ss << std::put_time(std::localtime(&in_time_t), "%Y%m%d-%H%M%S");
    custom_dir = ss.str();
  }

  optind = 2;
  int o;
  while ((o = getopt_long(argc, argv, "-hmidfFcTRO:M:l:e:", table, NULL)) !=
         -1) {
    switch (o) {
    case 'm':
      runtime_dump_opt.mem_buf = true;
      break;
      // case 'd': ccdb::runtime_dump_opt. = true; break;
    case 'f':
      runtime_dump_opt.frame_stk = true;
      break;
    case 'i':
      runtime_dump_opt.inst_buf = true;
      break;
    case 'l':
      wave_file = std::string(optarg);
      v_warn(options::wave_enable, "Fst wave not enabled. Recompile with LOGENA=1");
      break;
    case 'e':
      elf_file = optarg;
      break;
    case 'F':
      fast = true;
      break;
    case 'M':
      max_cycles = std::atoi(optarg);
      break;
    case 'R':
      record_perf = true;
      break;
    case 'O':
      custom_dir += "-" + std::string(optarg);
      break;
    case 'c':
      runtime_dump_opt.cycle_no = true;
      break;
    // case ArchConfig::ICacheSize:
    //   arch_config_val.insert_or_assign(ICacheSize, parse_size(optarg));
    //   break;
    // case ArchConfig::ICacheAssoc:
    //   arch_config_val.insert_or_assign(ICacheAssoc, atoi(optarg));
    //   break;
    // case ArchConfig::ICacheBlock:
    //   arch_config_val.insert_or_assign(ICacheBlock, atoi(optarg));
    //   break;
    default:
      std::cerr << ANSI_RED << "Invalid Argument 0x" << std::hex << o << "\n"
                << ANSI_NONE << std::endl;
      exit(1);
    }
  }

  outdir = std::filesystem::absolute("./ccout/" + custom_dir).string();
}

} // namespace options
