#include <klib.h>
#include <klib-macros.h>
#include <stdint.h>

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)

size_t strlen(const char *s) {
  // panic("Not implemented");
  return 0;
}

char *strcpy(char *dst, const char *src) {
  // panic("Not implemented");
  return NULL;
}

char *strncpy(char *dst, const char *src, size_t n) {
  // panic("Not implemented");
  return NULL;
}

char *strcat(char *dst, const char *src) {
  // panic("Not implemented");
  return NULL;
}

int strcmp(const char *s1, const char *s2) {
  // panic("Not implemented");
  return 0;
}

int strncmp(const char *s1, const char *s2, size_t n) {
  // panic("Not implemented");
  return 0;
}

void *memset(void *s, int c, size_t n) {
  // panic("Not implemented");
  return NULL;
}

void *memmove(void *dst, const void *src, size_t n) {
  // panic("Not implemented");
  return NULL;
}

void *memcpy(void *out, const void *in, size_t n) {
  // panic("Not implemented");
  return NULL;
}

int memcmp(const void *s1, const void *s2, size_t n) {
  // panic("Not implemented");
  return 0;
}

#endif
