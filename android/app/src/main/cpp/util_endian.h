#ifndef TUNNEL_FORGE_UTIL_ENDIAN_H
#define TUNNEL_FORGE_UTIL_ENDIAN_H

/* Big-endian helpers for protocol structs (see util_endian.c). */

#include <stdint.h>

void util_write_be16(uint8_t *p, uint16_t v);
void util_write_be32(uint8_t *p, uint32_t v);
uint16_t util_read_be16(const uint8_t *p);
uint32_t util_read_be32(const uint8_t *p);

#endif
