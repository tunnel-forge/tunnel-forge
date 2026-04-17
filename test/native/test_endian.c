#include <assert.h>
#include <stdint.h>

#include "util_endian.h"

int main(void) {
  uint8_t b[4];

  util_write_be16(b, 0x0102);
  assert(util_read_be16(b) == 0x0102);

  util_write_be32(b, 0x01020304u);
  assert(util_read_be32(b) == 0x01020304u);

  util_write_be16(b, 0);
  assert(util_read_be16(b) == 0);

  return 0;
}
