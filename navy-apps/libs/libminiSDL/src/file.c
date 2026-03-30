#include <sdl-file.h>
#include <stdlib.h>
#include <string.h>

static int64_t file_size(struct SDL_RWops *f) {
  long cur = ftell(f->fp);
  fseek(f->fp, 0, SEEK_END);
  long size = ftell(f->fp);
  fseek(f->fp, cur, SEEK_SET);
  return size;
}

static int64_t file_seek(struct SDL_RWops *f, int64_t offset, int whence) {
  if (fseek(f->fp, offset, whence) != 0) {
    return -1;
  }
  return ftell(f->fp);
}

static size_t file_read(struct SDL_RWops *f, void *buf, size_t size, size_t nmemb) {
  return fread(buf, size, nmemb, f->fp);
}

static size_t file_write(struct SDL_RWops *f, const void *buf, size_t size, size_t nmemb) {
  return fwrite(buf, size, nmemb, f->fp);
}

static int file_close(struct SDL_RWops *f) {
  int ret = fclose(f->fp);
  free(f);
  return ret;
}

static int64_t mem_size(struct SDL_RWops *f) {
  return f->mem.size;
}

static int64_t mem_seek(struct SDL_RWops *f, int64_t offset, int whence) {
  uint8_t *base = (uint8_t *)f->mem.base;
  uint8_t *cur = (uint8_t *)f->fp;
  int64_t pos = cur - base;
  switch (whence) {
    case RW_SEEK_SET: pos = offset; break;
    case RW_SEEK_CUR: pos += offset; break;
    case RW_SEEK_END: pos = f->mem.size + offset; break;
    default: return -1;
  }
  if (pos < 0 || pos > f->mem.size) {
    return -1;
  }
  f->fp = (FILE *)(base + pos);
  return pos;
}

static size_t mem_read(struct SDL_RWops *f, void *buf, size_t size, size_t nmemb) {
  if (size == 0 || nmemb == 0) {
    return 0;
  }
  size_t bytes = size * nmemb;
  uint8_t *base = (uint8_t *)f->mem.base;
  uint8_t *cur = (uint8_t *)f->fp;
  size_t remain = f->mem.size - (cur - base);
  if (bytes > remain) {
    bytes = remain - remain % size;
  }
  memcpy(buf, cur, bytes);
  f->fp = (FILE *)(cur + bytes);
  return size == 0 ? 0 : bytes / size;
}

static size_t mem_write(struct SDL_RWops *f, const void *buf, size_t size, size_t nmemb) {
  if (size == 0 || nmemb == 0) {
    return 0;
  }
  size_t bytes = size * nmemb;
  uint8_t *base = (uint8_t *)f->mem.base;
  uint8_t *cur = (uint8_t *)f->fp;
  size_t remain = f->mem.size - (cur - base);
  if (bytes > remain) {
    bytes = remain - remain % size;
  }
  memcpy(cur, buf, bytes);
  f->fp = (FILE *)(cur + bytes);
  return size == 0 ? 0 : bytes / size;
}

static int mem_close(struct SDL_RWops *f) {
  free(f);
  return 0;
}

SDL_RWops* SDL_RWFromFile(const char *filename, const char *mode) {
  FILE *fp = fopen(filename, mode);
  if (fp == NULL) {
    return NULL;
  }

  SDL_RWops *ops = (SDL_RWops *)malloc(sizeof(SDL_RWops));
  if (ops == NULL) {
    fclose(fp);
    return NULL;
  }

  *ops = (SDL_RWops) {
    .size = file_size,
    .seek = file_seek,
    .read = file_read,
    .write = file_write,
    .close = file_close,
    .type = RW_TYPE_FILE,
    .fp = fp,
  };
  return ops;
}

SDL_RWops* SDL_RWFromMem(void *mem, int size) {
  SDL_RWops *ops = (SDL_RWops *)malloc(sizeof(SDL_RWops));
  if (ops == NULL) {
    return NULL;
  }

  *ops = (SDL_RWops) {
    .size = mem_size,
    .seek = mem_seek,
    .read = mem_read,
    .write = mem_write,
    .close = mem_close,
    .type = RW_TYPE_MEM,
    .fp = (FILE *)mem,
    .mem = {
      .base = mem,
      .size = size,
    },
  };
  return ops;
}
