#ifndef TUNNEL_FORGE_UTIL_ENDIAN_H
#define TUNNEL_FORGE_UTIL_ENDIAN_H

/* Big-endian helpers for on-wire IKE/L2TP/ESP/PPP (see util_endian.c). */

#include <stdint.h>

/** @p p must point to at least 2 bytes. */
void util_write_be16(uint8_t *p, uint16_t v);
/** @p p must point to at least 4 bytes. */
void util_write_be32(uint8_t *p, uint32_t v);
uint16_t util_read_be16(const uint8_t *p);
uint32_t util_read_be32(const uint8_t *p);

#endif
