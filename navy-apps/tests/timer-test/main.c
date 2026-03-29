#include <sys/time.h>
#include <unistd.h>
#include <stdio.h>

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
