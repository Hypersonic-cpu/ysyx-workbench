#include <am.h>
#include <klib.h>
#include <klib-macros.h>
#include <stdarg.h>

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)

int printf(const char *fmt, ...) {
  char buffer[PRINT_BUF_LEN] = {0};
  va_list args;
  va_start(args, fmt);
  int ret = vsnprintf(buffer, PRINT_BUF_LEN, fmt, args);
  va_end(args);
  putstr(buffer);
  return ret;
}

// TODO: Move all main logics into vnsprintf 
int vsprintf(char *out, const char *fmt, va_list ap) {
  return vsnprintf(out, /* size_t */ -1, fmt, ap);
}

int sprintf(char *out, const char *fmt, ...) {
  va_list args;
  va_start(args, fmt);
  int ret = vsprintf(out, fmt, args);
  va_end(args);
  return ret;
}

int snprintf(char *out, size_t n, const char *fmt, ...) {
  va_list args;
  va_start(args, fmt);
  int ret = vsnprintf(out, n, fmt, args);
  va_end(args);
  return ret;
}

int vsnprintf(char *out, size_t n, const char *fmt, va_list ap) {
  char* const orig_out = out;
  /** 0:text, 1:after% */
  unsigned char state = 0;
  size_t cnt = 0;
  while (*fmt != '\0') {
    if (state == 0) {
      // text before current pos 
      if (*fmt == '%') {
        state = 1;
      } else {
        if (cnt++ + 1 >= n) { goto vnfinish; }
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
            int outv = va_arg(ap, int);
            // int64_t have 20 digits at most (including neg sign)
            // so 20 Byte buffer is enough
            char outbuf[20] = {0};
            unsigned bufptr = 0;
            if (outv < 0) {
              if (cnt++ + 1 >= n) { goto vnfinish; }
              *out++ = '-';
              outv = -outv;
            }
            do {
              outbuf[bufptr++] = (outv % 10) + '0';
              outv /= 10;
            } while (outv);

            while (bufptr--) {
              if (cnt++ + 1 >= n) { goto vnfinish; }
              *out++ = outbuf[bufptr];
            }
          }
          break;
        case 's': 
          {
            const char* outs = va_arg(ap, const char*);
            while (*outs != '\0') {
              if (cnt++ + 1 >= n) { goto vnfinish; }
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

vnfinish:
  *out++ = '\0';
  cnt++;
  if (cnt != (size_t) (out - orig_out)) { 
    putch(cnt/10+'0'); putch(cnt%10+'0'); putch('\n');
    assert(0);
  }; 
  return cnt;
}

#endif
