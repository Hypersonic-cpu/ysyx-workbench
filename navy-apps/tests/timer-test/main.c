#include <sys/time.h>
#include <unistd.h>
#include <stdio.h>

// static char *append_str(char *out, const char *s) {
//   while (*s != '\0') {
//     *out++ = *s++;
//   }
//   return out;
// }
//
// static char *append_ulong(char *out, unsigned long value) {
//   char buf[16];
//   int n = 0;
//   do {
//     buf[n++] = '0' + value % 10;
//     value /= 10;
//   } while (value != 0);
//   while (n > 0) {
//     *out++ = buf[--n];
//   }
//   return out;
// }
//
// static char *append_long(char *out, long value) {
//   if (value < 0) {
//     *out++ = '-';
//     return append_ulong(out, (unsigned long)(-value));
//   }
//   return append_ulong(out, (unsigned long)value);
// }
//
// static char *append_usec(char *out, long usec) {
//   unsigned long value = (unsigned long)usec;
//   unsigned long div = 100000;
//   while (div != 0) {
//     *out++ = '0' + value / div;
//     value %= div;
//     div /= 10;
//   }
//   return out;
// }
//
// static void print_uptime(int count, const struct timeval *tv) {
//   char buf[64];
//   char *out = buf;
//   out = append_str(out, "[");
//   out = append_long(out, count);
//   out = append_str(out, "] Curr uptime: ");
//   out = append_long(out, tv->tv_sec);
//   *out++ = '.';
//   out = append_usec(out, tv->tv_usec);
//   out = append_str(out, " s\n");
//   write(1, buf, out - buf);
// }

int main() {
  struct timeval tv;
  gettimeofday(&tv, NULL);

  long last_sec = tv.tv_sec;
  long last_usec = tv.tv_usec;
  int count = 0;

  while (1) {
    gettimeofday(&tv, NULL);

    long elapsed_usec =
        (tv.tv_sec - last_sec) * 1000000L + (tv.tv_usec - last_usec);

    // print_uptime(count, &tv);
    if (elapsed_usec >= 500000L) {
      count++;
      // print_uptime(count, &tv);
      printf("[%d] Time sec = %ld.%06ld\n", count, tv.tv_sec,
             tv.tv_usec);

      last_sec = tv.tv_sec;
      last_usec = tv.tv_usec;
    }
  }

  return 0;
}
