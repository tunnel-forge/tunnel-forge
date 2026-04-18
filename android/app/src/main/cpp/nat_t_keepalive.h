#ifndef TUNNEL_FORGE_NAT_T_KEEPALIVE_H
#define TUNNEL_FORGE_NAT_T_KEEPALIVE_H

#include <stddef.h>
#include <stdint.h>
#include <time.h>

#define NAT_T_KEEPALIVE_INTERVAL_SECS 15
#define NAT_T_KEEPALIVE_PAYLOAD_BYTE 0xffu

int nat_t_keepalive_is_probe(const uint8_t *buf, size_t len);

size_t nat_t_keepalive_write_probe(uint8_t *buf, size_t cap);

int nat_t_keepalive_is_due(time_t now, time_t last_outbound, time_t interval_secs);

#endif
