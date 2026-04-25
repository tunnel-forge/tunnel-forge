#include "../../android/app/src/main/cpp/ppp_mschap.h"

#include <stdio.h>
#include <string.h>

static int fail(const char *msg) {
  fprintf(stderr, "FAIL: %s\n", msg);
  return 1;
}

int main(void) {
  const uint8_t failure[] = "E=691 R=1 C=0123456789ABCDEF0123456789ABCDEF V=3 M=Authentication failed";
  ppp_mschapv2_failure_info_t info;
  if (ppp_mschapv2_parse_failure(failure, sizeof(failure) - 1u, &info) != 0) return fail("parse");
  if (!info.has_error_code || info.error_code != 691) return fail("error code");
  if (!info.has_retry || info.retry != 1) return fail("retry");
  if (!info.has_version || info.version != 3) return fail("version");
  if (!info.has_message || strcmp(info.message, "Authentication failed") != 0) return fail("message");

  const uint8_t partial[] = "E=648 R=0";
  if (ppp_mschapv2_parse_failure(partial, sizeof(partial) - 1u, &info) != 0) return fail("partial parse");
  if (!info.has_error_code || info.error_code != 648) return fail("partial error");
  if (!info.has_retry || info.retry != 0) return fail("partial retry");
  if (info.has_version || info.has_message) return fail("partial absent fields");

  const uint8_t invalid[] = "not an mschap failure";
  if (ppp_mschapv2_parse_failure(invalid, sizeof(invalid) - 1u, &info) == 0) return fail("invalid accepted");

  printf("ok\n");
  return 0;
}
