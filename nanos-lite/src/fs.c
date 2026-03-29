#include "debug.h"
#include <fs.h>
#include <string.h>

typedef size_t (*ReadFn)(void *buf, size_t offset, size_t len);
typedef size_t (*WriteFn)(const void *buf, size_t offset, size_t len);

size_t serial_write(const void *buf, size_t offset, size_t len);

typedef struct {
  char *name;
  size_t size;
  size_t disk_offset;
  ReadFn read;
  WriteFn write;
} Finfo;

typedef struct {
  int finfo_idx;
  int ref_count;
  size_t open_offset;
} Fopened;

enum { FD_STDIN, FD_STDOUT, FD_STDERR, FD_FB };

size_t invalid_read(void *buf, size_t offset, size_t len) {
  panic("should not reach here");
  return 0;
}

size_t invalid_write(const void *buf, size_t offset, size_t len) {
  panic("should not reach here");
  return 0;
}

/* This is the information about all files in disk. */
static Finfo file_table[] __attribute__((used)) = {
    [FD_STDIN] = {"stdin", 0, 0, invalid_read, invalid_write},
    [FD_STDOUT] = {"stdout", 0, 0, invalid_read, serial_write},
    [FD_STDERR] = {"stderr", 0, 0, invalid_read, serial_write},
#include "files.h"
    {NULL, 0, 0}};

#define MAX_FD 128
static Fopened file_opened[MAX_FD] __attribute__((used)) = {};

size_t ramdisk_read(void *buf, size_t offset, size_t len);
size_t ramdisk_write(const void *buf, size_t offset, size_t len);

void init_fs() {
  for (int i = 0; i < MAX_FD; i++) {
    file_opened[i].finfo_idx = -1;
  }
  file_opened[FD_STDOUT] = (Fopened){.finfo_idx = FD_STDOUT};
  file_opened[FD_STDERR] = (Fopened){.finfo_idx = FD_STDERR};
  // TODO: initialize the size of /dev/fb
}

int fs_open(const char *pathname, int flags, int mode) {
  int idx = 0;
  while (file_table[idx].name) {
    if (strcmp(file_table[idx].name, pathname) == 0) {
      Log("fs_open: find file '%s' @Finfo %d", pathname, idx);
      assert(file_opened[idx].finfo_idx == -1);
      file_opened[idx] =
          (Fopened){.finfo_idx = idx, .ref_count = 1, .open_offset = 0};
      return idx;
    }
    idx++;
  }
  Log("fs_open: No such file '%s'", pathname);
  return -1;
}

size_t fs_read(int fd, void *buf, size_t len) {
  if (file_opened[fd].finfo_idx < 0) {
    assert(false && "Not opened");
    return -1;
  }
  Finfo *finfo = &file_table[file_opened[fd].finfo_idx];
  ReadFn rd_handler = finfo->read ? finfo->read : ramdisk_read;
  size_t curr_off = finfo->disk_offset + file_opened[fd].open_offset;
  if (file_opened[fd].open_offset >= finfo->size) {
    return 0;
  }
  size_t remain = finfo->size - file_opened[fd].open_offset;
  size_t op_len = len < remain ? len : remain;
  size_t op_ret = rd_handler(buf, curr_off, op_len);
  file_opened[fd].open_offset += op_ret;
  return op_ret;
}

size_t fs_write(int fd, const void *buf, size_t len) {
  if (file_opened[fd].finfo_idx < 0) {
    assert(false && "Not opened");
    return -1;
  }
  Finfo *finfo = &file_table[file_opened[fd].finfo_idx];
  WriteFn wr_handler = finfo->write ? finfo->write : ramdisk_write;
  size_t curr_off = finfo->disk_offset + file_opened[fd].open_offset;
  size_t op_len = len;
  if (wr_handler == ramdisk_write) {
    if (file_opened[fd].open_offset >= finfo->size) {
      return 0;
    }
    size_t remain = finfo->size - file_opened[fd].open_offset;
    op_len = len < remain ? len : remain;
  }
  size_t op_ret = wr_handler(buf, curr_off, op_len);
  file_opened[fd].open_offset += op_ret;
  return op_ret;
}

size_t fs_lseek(int fd, size_t offset, int whence) {
  if (file_opened[fd].finfo_idx < 0) {
    return -1;
  }
  switch (whence) {
  case SEEK_SET:
    file_opened[fd].open_offset = offset;
    break;
  case SEEK_CUR:
    file_opened[fd].open_offset += offset;
    break;
  case SEEK_END:
    file_opened[fd].open_offset =
        file_table[file_opened[fd].finfo_idx].size + offset;
    break;
  default:
    assert(false && "No such whence");
    return -1;
  }
  return file_opened[fd].open_offset;
}

int fs_close(int fd) {
  file_opened[fd].ref_count--;
  file_opened[fd].finfo_idx = -1;
  file_opened[fd].open_offset = 0;
  return 0; // TODO:
}
