#pragma once

namespace diff {
  typedef void (*fn_t) ();
  extern fn_t ref_exec;
  extern fn_t ref_init;
  extern fn_t ref_memcpy;
  extern fn_t ref_raise_intr;
  extern fn_t ref_regcpy;

  bool match();
  void iota();
  void init();
}
