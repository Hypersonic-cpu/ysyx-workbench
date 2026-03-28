#include "syscall.h"
#include "am.h"
#include "debug.h"
#include <common.h>

const char *SyscallName[] = {
    [SYS_exit] = "SysExit",
    [SYS_yield] = "SysYield",
    [SYS_open] = "SysOpen",
    [SYS_read] = "SysRead",
    [SYS_write] = "SysWrite",
    [SYS_kill] = "SysKill",
    [SYS_getpid] = "SysGetpid",
    [SYS_close] = "SysClose",
    [SYS_lseek] = "SysLseek",
    [SYS_brk] = "SysBrk",
    [SYS_fstat] = "SysFstat",
    [SYS_time] = "SysTime",
    [SYS_signal] = "SysSignal",
    [SYS_execve] = "SysExecve",
    [SYS_fork] = "SysFork",
    [SYS_link] = "SysLink",
    [SYS_unlink] = "SysUnlink",
    [SYS_wait] = "SysWait",
    [SYS_times] = "SysTimes",
    [SYS_gettimeofday] = "SysGettimeofday",
};

const char* sys_name(uint32_t id) {
  if (id > SYS_gettimeofday) {
    return "[[Unknown]]";
  }
  return SyscallName[id];
}

void do_syscall(Context *c) {
  uintptr_t a[4];
  a[0] = c->GPR1;
#if STRACE
  Log("strace %s @ mepc %08x", sys_name(a[0]), c->mepc);
#endif /* if STRACE */

  switch (c->GPR1) {
  case SYS_exit:
    // exit
    halt(c->GPR2);
    break;
  case SYS_yield:
    yield();
    c->GPRx = 0;
    break;
  default:
    panic("Unhandled syscall ID = %d", a[0]);
  }
}
