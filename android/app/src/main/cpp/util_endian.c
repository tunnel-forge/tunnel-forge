/* Big-endian read/write for on-the-wire IKE, L2TP, ESP, and PPP headers (network byte order). */
#include "util_endian.h"

void util_write_be16(uint8_t *p, uint16_t v) {
  p[0] = (uint8_t)((v >> 8) & 0xff);
  p[1] = (uint8_t)(v & 0xff);
}

void util_write_be32(uint8_t *p, uint32_t v) {
  p[0] = (uint8_t)((v >> 24) & 0xff);
  p[1] = (uint8_t)((v >> 16) & 0xff);
  p[2] = (uint8_t)((v >> 8) & 0xff);
  p[3] = (uint8_t)(v & 0xff);
}

uint16_t util_read_be16(const uint8_t *p) {
  return (uint16_t)((p[0] << 8) | p[1]);
}

uint32_t util_read_be32(const uint8_t *p) {
  return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3];
}
