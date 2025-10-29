#include <am.h>
#include <klib.h>
#include <klib-macros.h>
#include <stdarg.h>

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)

int printf(const char *fmt, ...) {
  panic("Not implemented");
}

int vsprintf(char *out, const char *fmt, va_list ap) {
  panic("Not implemented");
}

int sprintf(char *out, const char *fmt, ...) {
  char* const orig_out = out;
  /** 0:text, 1:after% */
  unsigned char state = 0;
  va_list args;
  va_start(args, fmt);
  while (*fmt != '\0') {
    if (state == 0) {
      // text before current pos 
      if (*fmt == '%') {
        state = 1;
      } else {
        *out++ = *fmt;
      }
    } else {
      // '%' state 
      state = 0;
      // FIXME: Currently assume only one char 
      // after '%'
      switch (*fmt) {
        case 'd': 
          {
            int outv = va_arg(args, int);
            // int64_t have 20 digits at most (including neg sign)
            // so 20 Byte buffer is enough
            char outbuf[20] = {0};
            unsigned bufptr = 0;
            if (outv < 0) {
              *out++ = '-';
              outv = -outv;
            }
            do {
              outbuf[bufptr++] = (outv % 10) + '0';
              outv /= 10;
            } while (outv);

            while (bufptr--) {
              *out++ = outbuf[bufptr];
            }
          }
          break;
        case 's': 
          {
            const char* outs = va_arg(args, const char*);
            while (*outs != '\0') {
              *out++ = *outs++;
            }
          }
          break;
        default:
          // Unknown format
          return -1;
      }
    }
    fmt++;
  }
  va_end(args);
  *out++ = '\0';
  return (int) (out - orig_out);
}

int snprintf(char *out, size_t n, const char *fmt, ...) {
  panic("Not implemented");
}

int vsnprintf(char *out, size_t n, const char *fmt, va_list ap) {
  panic("Not implemented");
}

#endif
