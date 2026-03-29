#include <NDL.h>
#include <sdl-video.h>
#include <assert.h>
#include <string.h>
#include <stdlib.h>

// Internal helpers

static inline int imax(int a, int b) { return a > b ? a : b; }
static inline int imin(int a, int b) { return a < b ? a : b; }

/* Clip a 1-D segment [*pos, *pos+*len) against [0, limit).
 * *other is the paired coordinate on the other surface and is adjusted
 * symmetrically.  All parameters are plain int — callers must copy
 * SDL_Rect fields (which are int16_t/uint16_t) into local ints first.
 * Returns 0 if the segment vanishes. */
static int clip1d(int *pos, int *len, int limit, int *other) {
  if (*pos < 0) {
    *len   += *pos;
    *other -= *pos;
    *pos    = 0;
  }
  *len = imin(*len, limit - *pos);
  return *len > 0;
}

void SDL_BlitSurface(SDL_Surface *src, SDL_Rect *srcrect,
                     SDL_Surface *dst, SDL_Rect *dstrect) {
  assert(dst && src);
  assert(dst->format->BitsPerPixel == src->format->BitsPerPixel);

  /* Copy rect fields into plain ints to avoid int16_t * / int * mismatch. */
  int sx = srcrect ? srcrect->x : 0;
  int sy = srcrect ? srcrect->y : 0;
  int dx = dstrect ? dstrect->x : 0;
  int dy = dstrect ? dstrect->y : 0;
  int w  = srcrect ? srcrect->w : src->w;
  int h  = srcrect ? srcrect->h : src->h;

  if (!clip1d(&sx, &w, src->w, &dx)) return;
  if (!clip1d(&sy, &h, src->h, &dy)) return;
  if (!clip1d(&dx, &w, dst->w, &sx)) return;
  if (!clip1d(&dy, &h, dst->h, &sy)) return;

  int bytes = src->format->BytesPerPixel;
  for (int j = 0; j < h; j++) {
    memcpy(dst->pixels + (dy + j) * dst->pitch + dx * bytes,
           src->pixels + (sy + j) * src->pitch + sx * bytes,
           w * bytes);
  }

  if (dstrect) { dstrect->w = w; dstrect->h = h; }
}

void SDL_FillRect(SDL_Surface *dst, SDL_Rect *dstrect, uint32_t color) {
  assert(dst);

  int x  = dstrect ? dstrect->x : 0;
  int y  = dstrect ? dstrect->y : 0;
  int x2 = dstrect ? dstrect->x + dstrect->w : dst->w;
  int y2 = dstrect ? dstrect->y + dstrect->h : dst->h;

  x  = imax(x,  0);
  y  = imax(y,  0);
  x2 = imin(x2, dst->w);
  y2 = imin(y2, dst->h);
  if (x >= x2 || y >= y2) return;

  int bytes = dst->format->BytesPerPixel;
  int cols  = x2 - x;
  for (int j = y; j < y2; j++) {
    uint8_t *row = dst->pixels + j * dst->pitch + x * bytes;
    if (bytes == 4) {
      uint32_t *p = (uint32_t *)row;
      for (int i = 0; i < cols; i++) p[i] = color;
    } else if (bytes == 1) {
      memset(row, (uint8_t)color, cols);
    } else {
      for (int i = 0; i < cols; i++)
        memcpy(row + i * bytes, &color, bytes);
    }
  }
}

void SDL_UpdateRect(SDL_Surface *s, int x, int y, int w, int h) {
  assert(s);
  if (!(s->flags & SDL_HWSURFACE)) return;

  if (w == 0) w = s->w;
  if (h == 0) h = s->h;

  int dummy = 0;
  if (!clip1d(&x, &w, s->w, &dummy)) return;
  if (!clip1d(&y, &h, s->h, &dummy)) return;

  uint32_t *pixels = malloc(w * h * sizeof(uint32_t));
  assert(pixels);

  if (s->format->BitsPerPixel == 32) {
    for (int j = 0; j < h; j++)
      memcpy(pixels + j * w,
             s->pixels + (y + j) * s->pitch + x * sizeof(uint32_t),
             w * sizeof(uint32_t));
  } else {
    assert(s->format->BitsPerPixel == 8);
    assert(s->format->palette);
    for (int j = 0; j < h; j++) {
      uint8_t *src_row = s->pixels + (y + j) * s->pitch + x;
      for (int i = 0; i < w; i++) {
        SDL_Color c = s->format->palette->colors[src_row[i]];
        pixels[j * w + i] = ((uint32_t)c.a << 24) | ((uint32_t)c.r << 16)
                           | ((uint32_t)c.g <<  8) | c.b;
      }
    }
  }

  NDL_DrawRect(pixels, x, y, w, h);
  free(pixels);
}

// APIs below are already implemented.

static inline int maskToShift(uint32_t mask) {
  switch (mask) {
    case 0x000000ff: return 0;
    case 0x0000ff00: return 8;
    case 0x00ff0000: return 16;
    case 0xff000000: return 24;
    case 0x00000000: return 24; // hack
    default: assert(0);
  }
}

SDL_Surface *SDL_CreateRGBSurface(uint32_t flags, int width, int height, int depth,
    uint32_t Rmask, uint32_t Gmask, uint32_t Bmask, uint32_t Amask) {
  assert(depth == 8 || depth == 32);
  SDL_Surface *s = malloc(sizeof(SDL_Surface));
  assert(s);
  s->flags  = flags;
  s->format = malloc(sizeof(SDL_PixelFormat));
  assert(s->format);

  if (depth == 8) {
    s->format->palette = malloc(sizeof(SDL_Palette));
    assert(s->format->palette);
    s->format->palette->colors = malloc(sizeof(SDL_Color) * 256);
    assert(s->format->palette->colors);
    memset(s->format->palette->colors, 0, sizeof(SDL_Color) * 256);
    s->format->palette->ncolors = 256;
  } else {
    s->format->palette = NULL;
    s->format->Rmask = Rmask; s->format->Rshift = maskToShift(Rmask); s->format->Rloss = 0;
    s->format->Gmask = Gmask; s->format->Gshift = maskToShift(Gmask); s->format->Gloss = 0;
    s->format->Bmask = Bmask; s->format->Bshift = maskToShift(Bmask); s->format->Bloss = 0;
    s->format->Amask = Amask; s->format->Ashift = maskToShift(Amask); s->format->Aloss = 0;
  }

  s->format->BitsPerPixel  = depth;
  s->format->BytesPerPixel = depth / 8;
  s->w     = width;
  s->h     = height;
  s->pitch = width * depth / 8;
  assert(s->pitch == width * s->format->BytesPerPixel);

  if (!(flags & SDL_PREALLOC)) {
    s->pixels = malloc(s->pitch * height);
    assert(s->pixels);
  }
  return s;
}

SDL_Surface *SDL_CreateRGBSurfaceFrom(void *pixels, int width, int height, int depth,
    int pitch, uint32_t Rmask, uint32_t Gmask, uint32_t Bmask, uint32_t Amask) {
  SDL_Surface *s = SDL_CreateRGBSurface(SDL_PREALLOC, width, height, depth,
      Rmask, Gmask, Bmask, Amask);
  assert(pitch == s->pitch);
  s->pixels = pixels;
  return s;
}

void SDL_FreeSurface(SDL_Surface *s) {
  if (s) {
    if (s->format) {
      if (s->format->palette) {
        free(s->format->palette->colors);
        free(s->format->palette);
      }
      free(s->format);
    }
    if (s->pixels && !(s->flags & SDL_PREALLOC)) free(s->pixels);
    free(s);
  }
}

SDL_Surface *SDL_SetVideoMode(int width, int height, int bpp, uint32_t flags) {
  if (flags & SDL_HWSURFACE) NDL_OpenCanvas(&width, &height);
  return SDL_CreateRGBSurface(flags, width, height, bpp,
      DEFAULT_RMASK, DEFAULT_GMASK, DEFAULT_BMASK, DEFAULT_AMASK);
}

void SDL_SoftStretch(SDL_Surface *src, SDL_Rect *srcrect,
                     SDL_Surface *dst, SDL_Rect *dstrect) {
  assert(src && dst);
  assert(dst->format->BitsPerPixel == src->format->BitsPerPixel);
  assert(dst->format->BitsPerPixel == 8);

  int x = srcrect ? srcrect->x : 0;
  int y = srcrect ? srcrect->y : 0;
  int w = srcrect ? srcrect->w : src->w;
  int h = srcrect ? srcrect->h : src->h;

  assert(dstrect);
  if (w == dstrect->w && h == dstrect->h) {
    SDL_Rect r = {x, y, w, h};
    SDL_BlitSurface(src, &r, dst, dstrect);
  } else {
    assert(0);
  }
}

void SDL_SetPalette(SDL_Surface *s, int flags, SDL_Color *colors,
                    int firstcolor, int ncolors) {
  assert(s && s->format && s->format->palette);
  assert(firstcolor == 0);

  s->format->palette->ncolors = ncolors;
  memcpy(s->format->palette->colors, colors, sizeof(SDL_Color) * ncolors);

  if (s->flags & SDL_HWSURFACE) {
    assert(ncolors == 256);
    SDL_UpdateRect(s, 0, 0, 0, 0);
  }
}

static void ConvertPixelsARGB_ABGR(void *dst, void *src, int len) {
  uint8_t (*pdst)[4] = dst;
  uint8_t (*psrc)[4] = src;
  for (int i = 0; i < len; i++) {
    uint32_t v;
    memcpy(&v, psrc[i], 4);
    memcpy(pdst[i], &v, 4);
    pdst[i][0] = psrc[i][2];
    pdst[i][2] = psrc[i][0];
  }
}

SDL_Surface *SDL_ConvertSurface(SDL_Surface *src, SDL_PixelFormat *fmt,
                                 uint32_t flags) {
  assert(src->format->BitsPerPixel == 32);
  assert(src->w * src->format->BytesPerPixel == src->pitch);
  assert(src->format->BitsPerPixel == fmt->BitsPerPixel);

  SDL_Surface *ret = SDL_CreateRGBSurface(flags, src->w, src->h,
      fmt->BitsPerPixel, fmt->Rmask, fmt->Gmask, fmt->Bmask, fmt->Amask);

  assert(fmt->Gmask == src->format->Gmask);
  assert(fmt->Amask == 0 || src->format->Amask == 0 ||
         fmt->Amask == src->format->Amask);

  ConvertPixelsARGB_ABGR(ret->pixels, src->pixels, src->w * src->h);
  return ret;
}

uint32_t SDL_MapRGBA(SDL_PixelFormat *fmt,
                     uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
  assert(fmt->BytesPerPixel == 4);
  uint32_t p = ((uint32_t)r << fmt->Rshift)
             | ((uint32_t)g << fmt->Gshift)
             | ((uint32_t)b << fmt->Bshift);
  if (fmt->Amask) p |= ((uint32_t)a << fmt->Ashift);
  return p;
}

int  SDL_LockSurface(SDL_Surface *s)   { return 0; }
void SDL_UnlockSurface(SDL_Surface *s) {}
