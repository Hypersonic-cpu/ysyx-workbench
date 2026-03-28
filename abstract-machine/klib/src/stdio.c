#include <am.h>
#include <klib-macros.h>
#include <klib.h>
#include <limits.h>
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
  char *const orig_out = out;
  /** 0:text, 1:after% */
  unsigned char state = 0;
  size_t cnt = 0;
  while (*fmt != '\0') {
    if (state == 0) {
      // text before current pos
      if (*fmt == '%') {
        state = 1;
      } else {
        if (cnt++ + 1 >= n) {
          goto vnfinish;
        }
        *out++ = *fmt;
      }
      fmt++;
    } else {
      // '%' state
      state = 0;
      // TODO: Left align?
      int width = 0;
      // int precision = INT_MAX;
      char padding = ' ';
      // TODO: %[$][flags][width][.precision][length modifier]conversion
      if (*fmt == '0') {
        padding = '0';
        fmt++;
      }
      while (*fmt >= '0' && *fmt < '9') {
        width = width * 10 + *fmt - '0';
        fmt++;
      }

      unsigned intbase = 10;
      bool intusgn = false;
      switch (*fmt++) {
      case 'p':
        width = sizeof(void *) << 1; // * 8 / 4
        // Fall through
      case 'x':
        intbase = 16;
        // Fall through
      case 'u':
        intusgn = true;
        // Fall through
      case 'd': {
        int outv = va_arg(ap, int);
        unsigned outu = 0xBadC0de;
        // int64_t have 20 digits at most (including neg sign)
        // so 20 Byte buffer is enough
        char outbuf[32] = {0};
        panic_on(width > 31, "integer width out-of-buffer");
        unsigned bufptr = 0;

        if (intusgn) {
          outu = *(unsigned *)(&outv);
        } else {
          if (outv < 0) {
            assert(intbase == 10 && "Non-DEC signed number");
            if (cnt++ + 1 >= n) {
              goto vnfinish;
            }
            *out++ = '-';
            outu = -outv;
          } else {
            outu = outv;
          }
        }
        do {
          if (outu % intbase < 10) {
            outbuf[bufptr++] = (outu % intbase) + '0';
          } else {
            outbuf[bufptr++] = (outu % intbase) - 10 + 'a';
          }
          outu /= intbase;
        } while (outu);

        // After this buffptr == width ([0] .. [width-1])
        while (bufptr < width) {
          outbuf[bufptr++] = padding;
        }

        while (bufptr--) {
          if (cnt++ + 1 >= n) {
            goto vnfinish;
          }
          *out++ = outbuf[bufptr];
        }
      } break;
      case 's': {
        const char *outs = va_arg(ap, const char *);
        while (*outs != '\0') {
          if (cnt++ + 1 >= n) {
            goto vnfinish;
          }
          *out++ = *outs++;
        }
      } break;
      case 'c': {
        const char outc = va_arg(ap, int);
        if (cnt++ + 1 >= n) {
          goto vnfinish;
        }
        *out++ = outc;
      } break;
      default:
        putstr("printf ERROR: Unknown ch ");
        putch(*(fmt - 1));
        putch('\n');
        halt(255);
        // Unknown format
        return -1;
      }
    }
  }

vnfinish:
  *out++ = '\0';
  cnt++;
  if (cnt != (size_t)(out - orig_out)) {
    putstr("printf ERROR: Overflow.\n");
    assert(0);
  };
  return cnt;
}

#endif
