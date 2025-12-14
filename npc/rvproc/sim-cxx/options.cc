#include "options.hh"
#include <getopt.h>

namespace options {

DumpPrintOpt runtime_dump_opt {false, false, false, false, false};
std::string wave_file = "";
std::string elf_file = "";
bool fast = false;
size_t max_cycles = ~0ULL;

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
    {"log", required_argument, NULL, 'l'},
    {"elf", required_argument, NULL, 'e'},
    {"help", no_argument, NULL, 'h'},
    {0, 0, NULL, 0},
  };

  optind = 2;
  int o;
  while ((o = getopt_long(argc, argv, "-hmidfFcTM:l:e:", table, NULL)) !=
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
      assert(wave_enable && "Fst wave not enabled. Recompile with LOGENA=1");
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
    case 'c':
      runtime_dump_opt.cycle_no = true;
      break;
    default:
      std::cerr << ANSI_RED << "Invalid Arguments.\n"
                << ANSI_NONE << std::endl;
      exit(1);
    }
  }
}

} // namespace options
