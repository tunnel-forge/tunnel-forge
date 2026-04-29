#ifndef TUNNEL_FORGE_NAT_T_KEEPALIVE_H
#define TUNNEL_FORGE_NAT_T_KEEPALIVE_H

/*
 * NAT-T keepalive (RFC 3948 style): single-byte UDP payload 0xff to refresh UDP state on middleboxes.
 */

#include <stddef.h>
#include <stdint.h>
#include <time.h>

/** Suggested minimum interval between application-initiated keepalives when idle. */
#define NAT_T_KEEPALIVE_INTERVAL_SECS 15
/** Wire payload for a NAT-T keepalive datagram. */
#define NAT_T_KEEPALIVE_PAYLOAD_BYTE 0xffu

/** @return 1 if buf is exactly one 0xff byte. */
int nat_t_keepalive_is_probe(const uint8_t *buf, size_t len);

/** Write keepalive into @p buf; @return 1 on success, 0 if cap < 1. */
size_t nat_t_keepalive_write_probe(uint8_t *buf, size_t cap);

/** @return 1 if now - last_outbound >= interval (or no prior send). */
int nat_t_keepalive_is_due(time_t now, time_t last_outbound, time_t interval_secs);

#endif
