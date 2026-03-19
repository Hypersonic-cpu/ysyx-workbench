#include <klib-macros.h>
#include <klib.h>
#include <stdint.h>

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)

#define HASZERO_32B(v) (((v) - 0x01010101UL) & ~(v) & 0x80808080UL)

// WARN: Untested case: non-null-terminated

size_t strlen(const char *s) {
  size_t len = 0;
  while (*s != '\0') {
    ++len, ++s;
  }
  return len;
}

char *strcpy(char *dst, const char *src) {
  char *ret = dst;

  while ((uintptr_t)src & 3) {
    if (!(*dst++ = *src++))
      return ret;
  }

  // Aligned case
  if (!((uintptr_t)dst & 3)) {
    const uint32_t *wsrc = (const uint32_t *)src;
    uint32_t *wdst = (uint32_t *)dst;

    while (true) {
      uint32_t w = *wsrc;
      if (HASZERO_32B(w))
        break;
      *wdst++ = w;
      wsrc++;
    }

    src = (const char *)wsrc;
    dst = (char *)wdst;
  }

  // Tail case
  while ((*dst++ = *src++))
    ;
  return ret;
}

char *strncpy(char *dst, const char *src, size_t n) {
  char *ret = dst;
  size_t i = 0;
  for (; i < n && src[i] != '\0'; i++)
    dst[i] = src[i];
  memset(dst + i, 0, n - i);
  return ret;
}

char *strcat(char *dst, const char *src) {
  strcpy(dst + strlen(dst), src);
  return dst;
}

int strcmp(const char *s1, const char *s2) {
  const unsigned char *uc1 = (const unsigned char *)s1;
  const unsigned char *uc2 = (const unsigned char *)s2;
  // Unaligned case
  if (((uintptr_t)s1 | (uintptr_t)s2) & 3)
    goto byte_cmp;
  else {
    const uint32_t *w1 = (const uint32_t *)uc1;
    const uint32_t *w2 = (const uint32_t *)uc2;

    while (true) {
      uint32_t v1 = *w1;
      uint32_t v2 = *w2;
      if (v1 != v2 || /* Equal case */ HASZERO_32B(v1)) {
        uc1 = (const unsigned char *)w1;
        uc2 = (const unsigned char *)w2;
        goto byte_cmp;
      }
      w1++, w2++;
    }
  }
byte_cmp:
  if (true) {
    // Escape when: all zero or different
    while (*uc1 && *uc1 == *uc2) {
      uc1++;
      uc2++;
    }
    return (int)*uc1 - (int)*uc2;
  }
}

// Not optimized
int strncmp(const char *s1, const char *s2, size_t n) {
  const unsigned char *uc1 = (const unsigned char *)s1;
  const unsigned char *uc2 = (const unsigned char *)s2;
  while (n--) {
    int c1 = *(uc1++);
    int c2 = *(uc2++);
    /** Either a mis-match or termination
     *  c1   c2   res
     *  0    N    ret
     *  N    0    not equal, ret
     *  0    0    ret 0
     */
    if (c1 != c2 || c1 == 0) {
      return c1 - c2;
    }
  }
  return 0;
}

void *memset(void *s, int c, size_t n) {
  unsigned char *ptr = (unsigned char *)s;
  unsigned char u8c = (unsigned char)c;

  while (n && ((uintptr_t)ptr & 3)) {
    *ptr++ = u8c;
    n--;
  }

  if (n >= 4) {
    uint32_t pattern = (uint32_t)u8c;
    pattern |= pattern << 8;
    pattern |= pattern << 16;

    uint32_t *wptr = (uint32_t *)ptr;
    size_t words = n / 4;
    while (words--) {
      *wptr++ = pattern;
    }
    ptr = (unsigned char *)wptr;
    n &= 3;
  }

  while (n--) {
    *ptr++ = u8c;
  }
  return s;
}

void *memmove(void *dst, const void *src, size_t n) {
  if (dst == src || n == 0) {
    return dst;
  }
  unsigned char *bdst = dst;
  const unsigned char *bsrc = src;
  /**
   * src [=======rrr]
   * dst    [==========www]
   *
   * src    [rrrr======]
   * dst [wwww======]
   */
  if (dst < src || src + n <= dst) {
    // Overwrite-safe when processing in-order
    return memcpy(dst, src, n);
  }

  // Reverse order
  for (size_t i = n; i > 0; i--) {
    bdst[i - 1] = bsrc[i - 1];
  }
  return dst;
}

void *memcpy(void *out, const void *in, size_t n) {
  unsigned char *dst = (unsigned char *)out;
  const unsigned char *src = (const unsigned char *)in;

  // Aligned case
  while (n && ((uintptr_t)dst & 3)) {
    *dst++ = *src++;
    n--;
  }
  if (!((uintptr_t)src & 3)) {
    uint32_t *wdst = (uint32_t *)dst;
    const uint32_t *wsrc = (const uint32_t *)src;
    // 4x Loop unroll
    while (n >= 16) {
      wdst[0] = wsrc[0];
      wdst[1] = wsrc[1];
      wdst[2] = wsrc[2];
      wdst[3] = wsrc[3];
      wdst += 4;
      wsrc += 4;
      n -= 16;
    }
    while (n >= 4) {
      *wdst++ = *wsrc++;
      n -= 4;
    }
    dst = (unsigned char *)wdst;
    src = (const unsigned char *)wsrc;
  }

  // Tail or Unaligned case
  while (n--)
    *dst++ = *src++;
  return out;
}

int memcmp(const void *s1, const void *s2, size_t n) {
  const unsigned char *uc1 = (const unsigned char *)s1;
  const unsigned char *uc2 = (const unsigned char *)s2;
  // Unaligned case
  if (n >= 4 && !(((uintptr_t)uc1 | (uintptr_t)uc2) & 3)) {
    const uint32_t *w1 = (const uint32_t *)uc1;
    const uint32_t *w2 = (const uint32_t *)uc2;

    while (n >= 4) {
      if (*w1 != *w2) {
        uc1 = (const unsigned char *)w1;
        uc2 = (const unsigned char *)w2;
        goto byte_cmp;
      }
      w1++, w2++;
      n -= 4;
    }
  }

byte_cmp:
  if (true) {
    // Escape when: all zero or different
    while (n--) {
      int diff = (int)*uc1++ - (int)*uc2++;
      if (diff)
        return diff;
    }
  }
  return 0;
}

#endif
