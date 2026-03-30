#include "syscall.h"
#include "am.h"
#include "debug.h"
#include "fs.h"
#include "proc.h"
#include <common.h>
#include <stdint.h>
#include <sys/time.h>

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

void naive_uload(PCB *pcb, const char *filename);

uintptr_t do_sys_exit(uintptr_t real_args[3]);
uintptr_t do_sys_yield(uintptr_t real_args[3]);
uintptr_t do_sys_write(uintptr_t real_args[3]);
uintptr_t do_sys_brk(uintptr_t real_args[3]);
uintptr_t do_sys_gettime(uintptr_t real_args[3]);
uintptr_t do_sys_open(uintptr_t real_args[3]);
uintptr_t do_sys_read(uintptr_t real_args[3]);
uintptr_t do_sys_lseek(uintptr_t real_args[3]);
uintptr_t do_sys_close(uintptr_t real_args[3]);
uintptr_t do_sys_execve(uintptr_t real_args[3]);

syshandle_t *SyscallHandlers[] = {
    [SYS_exit] = do_sys_exit,
    [SYS_yield] = do_sys_yield,
    [SYS_write] = do_sys_write,
    [SYS_brk] = do_sys_brk,
    [SYS_open] = do_sys_open,
    [SYS_read] = do_sys_read,
    [SYS_lseek] = do_sys_lseek,
    [SYS_close] = do_sys_close,
    [SYS_gettimeofday] = do_sys_gettime,
    [SYS_execve] = do_sys_execve,
};

const char *sys_name(uintptr_t id) {
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
  case SYS_gettimeofday:
  case SYS_open:
  case SYS_read:
  case SYS_lseek:
  case SYS_close:
  case SYS_execve:
    c->GPRx = SyscallHandlers[a[0]](&(a[1]));
    break;
  default:
    panic("Unhandled syscall ID = %d", a[0]);
  }
}

uintptr_t do_sys_exit(uintptr_t ra[3]) {
  naive_uload(NULL, "/bin/menu");
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
  return fs_write(fd, buf, len);
}

uintptr_t do_sys_brk(uintptr_t ra[3]) { return 0; }

uintptr_t do_sys_gettime(uintptr_t ra[3]) {
  struct timeval *tv = (struct timeval *)ra[0];
  struct timezone *tz_nullable = (struct timezone *)ra[1];
  assert(tz_nullable == NULL);
  AM_TIMER_UPTIME_T amt;
  ioe_read(AM_TIMER_UPTIME, &amt);
  tv->tv_sec = amt.us / 1000000;
  tv->tv_usec = amt.us % 1000000;
  return 0;
}

uintptr_t do_sys_open(uintptr_t ra[3]) {
  return fs_open((const char *)ra[0], ra[1], ra[2]);
}

uintptr_t do_sys_read(uintptr_t ra[3]) {
  return fs_read(ra[0], (void *)ra[1], ra[2]);
}

uintptr_t do_sys_lseek(uintptr_t ra[3]) {
  return fs_lseek(ra[0], ra[1], ra[2]);
}

uintptr_t do_sys_close(uintptr_t ra[3]) { return fs_close(ra[0]); }

uintptr_t do_sys_execve(uintptr_t ra[3]) {
  const char *filename = (const char *)ra[0];
  assert(filename != NULL);
  naive_uload(NULL, filename);
  return 0;
}
