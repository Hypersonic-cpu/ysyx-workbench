#include <klib.h>
#include <klib-macros.h>
#include <stdint.h>

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)

// WARN: 没有测试过 empty/non-null-terminated 等特殊情况.


size_t strlen(const char *s) {
  size_t len = 0;
  while (*s != '\0') { ++len, ++s; }
  return len;
}

char *strcpy(char *dst, const char *src) {
  const char* cur = src;
  char* tar = dst;
  while (*cur != '\0') {
    *tar = *cur;
    tar++, cur++;
  }
  *tar = '\0';
  return dst;
}

char *strncpy(char *dst, const char *src, size_t n) {
  const char* cur = src;
  char* tar = dst;
  size_t now = 0;
  while (now < n && *cur != '\0') {
    *tar = *cur;
    tar++, cur++, now++;
  }
  // TODO: change to memset
  while (now < n) {
    *tar = '\0';
    tar++, now++;
  }
  return dst;
}

char *strcat(char *dst, const char *src) {
  strcpy(dst + strlen(dst), src);
  return dst;
}

int strcmp(const char *s1, const char *s2) {
  const unsigned char* uc1 = (const unsigned char*) s1;
  const unsigned char* uc2 = (const unsigned char*) s2;
  while (*uc1 != '\0' && *uc2 != '\0') {
    int c1 = *(uc1++);
    int c2 = *(uc2++);
    if (c1 == c2) { continue; }
    return c1 - c2;
  }
  return (int)(*uc1) - (int)(*uc2);
}

int strncmp(const char *s1, const char *s2, size_t n) {
  const unsigned char* uc1 = (const unsigned char*) s1;
  const unsigned char* uc2 = (const unsigned char*) s2;
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
  unsigned char* ptr = (unsigned char*) s;
  while (n--) {
    *ptr = (unsigned char) c;
    ptr++;
  }
  return s;
}

void *memmove(void *dst, const void *src, size_t n) {
  if (dst == src) { return dst; }
  unsigned char* bdst = dst;
  const unsigned char* bsrc = src;
  /**
   * src [=======rrr]
   * dst    [==========www]
   *
   * src    [rrrr======]
   * dst [wwww======]
   */
  if (dst > src) {
    for (size_t i = n; i > 0; i--) {
      bdst[i-1] = bsrc[i-1];
    }
  } else {
    for (size_t i = 0; i < n; i++) {
      bdst[i] = bsrc[i];
    }
  }
  return dst;
}

void *memcpy(void *out, const void *in, size_t n) {
  unsigned char* bdst = out;
  const unsigned char* bsrc = in;
  // TODO: Optimize it 
  while (n--) {
    *(bdst++) = *(bsrc++);
  }
  return out;
}

int memcmp(const void *s1, const void *s2, size_t n) {
  // TODO: Optimize 
  const unsigned char* uc1 = (const unsigned char*) s1;
  const unsigned char* uc2 = (const unsigned char*) s2;
  while (n--) {
    int c1 = *(uc1++);
    int c2 = *(uc2++);
    if (c1 == c2) { continue; }
    return c1 - c2;
  }
  return 0;
}

#endif
