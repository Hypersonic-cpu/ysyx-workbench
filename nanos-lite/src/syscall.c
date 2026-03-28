#include "syscall.h"
#include "am.h"
#include "debug.h"
#include <common.h>
#include <stdint.h>

const char *SyscallName[] = {
    [SYS_exit] = "SysExit",     [SYS_yield] = "SysYield",
    [SYS_open] = "SysOpen",     [SYS_read] = "SysRead",
    [SYS_write] = "SysWrite",   [SYS_kill] = "SysKill",
    [SYS_getpid] = "SysGetpid", [SYS_close] = "SysClose",
    [SYS_lseek] = "SysLseek",   [SYS_brk] = "SysBrk",
    [SYS_fstat] = "SysFstat",   [SYS_time] = "SysTime",
    [SYS_signal] = "SysSignal", [SYS_execve] = "SysExecve",
    [SYS_fork] = "SysFork",     [SYS_link] = "SysLink",
    [SYS_unlink] = "SysUnlink", [SYS_wait] = "SysWait",
    [SYS_times] = "SysTimes",   [SYS_gettimeofday] = "SysGettimeofday",
};

typedef uintptr_t syshandle_t(uintptr_t real_args[3]);

uintptr_t do_sys_exit(uintptr_t real_args[3]);
uintptr_t do_sys_yield(uintptr_t real_args[3]);
uintptr_t do_sys_write(uintptr_t real_args[3]);
uintptr_t do_sys_brk(uintptr_t real_args[3]);

syshandle_t *SyscallHandlers[] = {
    [SYS_exit] = do_sys_exit,
    [SYS_yield] = do_sys_yield,
    [SYS_write] = do_sys_write,
    [SYS_brk] = do_sys_brk,
};

const char *sys_name(uint32_t id) {
  if (id > SYS_gettimeofday) {
    return "[[Unknown]]";
  }
  return SyscallName[id];
}

void do_syscall(Context *c) {
  uintptr_t a[4];
  a[0] = c->GPR1;
  a[1] = c->GPR2;
  a[2] = c->GPR3;
  a[3] = c->GPR4;
#if STRACE
  Log("strace %s @ mepc %08x (args[1]=%08x [2]=%08x [3]=%08x)", sys_name(a[0]),
      c->mepc, a[1], a[2], a[3]);
#endif /* if STRACE */

  switch (a[0]) {
  case SYS_exit:
  case SYS_yield:
  case SYS_write:
  case SYS_brk:
    c->GPRx = SyscallHandlers[a[0]](&(a[1]));
    break;
  default:
    panic("Unhandled syscall ID = %d", a[0]);
  }
}

uintptr_t do_sys_exit(uintptr_t ra[3]) {
  halt(ra[0]);
  return ra[0];
}

uintptr_t do_sys_yield(uintptr_t ra[3]) {
  yield();
  return 0;
}

uintptr_t do_sys_write(uintptr_t ra[3]) {
  int fd = ra[0];
  unsigned char *buf = (unsigned char *)ra[1];
  size_t len = ra[2];
  if (fd == 1 || fd == 2) {
    // Call AM's write
    for (size_t i = 0; i < len; i++) {
      putch(buf[i]);
    }
    return len;
  } else {
    assert(false && "Non-stdout|stderr output");
    return -1;
  }
}

uintptr_t do_sys_brk(uintptr_t ra[3]) {
  return 0;
}
