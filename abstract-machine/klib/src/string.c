#include <klib.h>
#include <klib-macros.h>
#include <stdint.h>

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)

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
  while (now < n) {
    *tar = '\0';
    tar++, now++;
  }
  return dst;
}

char *strcat(char *dst, const char *src) {
  // panic("Not implemented");
  return NULL;
}

int strcmp(const char *s1, const char *s2) {
  while (*s1 != '\0' && *s2 != '\0') {
    int c1 = *((const unsigned char*) s1++);
    int c2 = *((const unsigned char*) s2++);
    if (c1 == c2) { continue; }
    return c1 - c2;
  }
  return 0;
}

int strncmp(const char *s1, const char *s2, size_t n) {
  size_t len = 0;
  while (*s1 != '\0' && *s2 != '\0' && len++ < n) {
    int c1 = *((const unsigned char*) s1++);
    int c2 = *((const unsigned char*) s2++);
    if (c1 == c2) { continue; }
    return c1 - c2;
  }
  return 0;
}

void *memset(void *s, int c, size_t n) {
  panic("Not implemented");
  return NULL;
}

void *memmove(void *dst, const void *src, size_t n) {
  panic("Not implemented");
  return NULL;
}

void *memcpy(void *out, const void *in, size_t n) {
  panic("Not implemented");
  return NULL;
}

int memcmp(const void *s1, const void *s2, size_t n) {
  panic("Not implemented");
  return 0;
}

#endif
