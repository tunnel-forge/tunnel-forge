#include "nat_t_keepalive.h"

int nat_t_keepalive_is_probe(const uint8_t *buf, size_t len) {
  return buf != NULL && len == 1u && buf[0] == NAT_T_KEEPALIVE_PAYLOAD_BYTE;
}

size_t nat_t_keepalive_write_probe(uint8_t *buf, size_t cap) {
  if (buf == NULL || cap < 1u) return 0u;
  buf[0] = NAT_T_KEEPALIVE_PAYLOAD_BYTE;
  return 1u;
}

int nat_t_keepalive_is_due(time_t now, time_t last_outbound, time_t interval_secs) {
  if (interval_secs <= 0) return 0;
  if (last_outbound == 0) return 1;
  if (now < last_outbound) return 0;
  return (now - last_outbound) >= interval_secs;
}
