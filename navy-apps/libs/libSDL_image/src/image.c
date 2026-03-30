#define SDL_malloc  malloc
#define SDL_free    free
#define SDL_realloc realloc

#define SDL_STBIMAGE_IMPLEMENTATION
#include "SDL_stbimage.h"
#include <stdlib.h>
#include <string.h>

SDL_Surface* IMG_Load_RW(SDL_RWops *src, int freesrc) {
  assert(src);

  int size = SDL_RWsize(src);
  if (size < 0) {
    if (freesrc) SDL_RWclose(src);
    return NULL;
  }

  uint8_t *buf = (uint8_t *)malloc(size);
  if (buf == NULL) {
    if (freesrc) SDL_RWclose(src);
    return NULL;
  }

  SDL_RWseek(src, 0, RW_SEEK_SET);
  size_t nread = SDL_RWread(src, buf, 1, size);
  SDL_Surface *ret = (nread == (size_t)size) ? STBIMG_LoadFromMemory(buf, size) : NULL;
  free(buf);

  if (freesrc) {
    SDL_RWclose(src);
  }
  return ret;
}

SDL_Surface* IMG_Load(const char *filename) {
  SDL_RWops *src = SDL_RWFromFile(filename, "rb");
  if (src == NULL) {
    return NULL;
  }
  return IMG_Load_RW(src, 1);
}

int IMG_isPNG(SDL_RWops *src) {
  return 0;
}

SDL_Surface* IMG_LoadJPG_RW(SDL_RWops *src) {
  return IMG_Load_RW(src, 0);
}

char *IMG_GetError() {
  return "Navy does not support IMG_GetError()";
}
