#include "../../android/app/src/main/cpp/nat_t_keepalive.h"

#include <stdint.h>
#include <stdio.h>
#include <time.h>

static int test_single_byte_ff_is_probe(void) {
  const uint8_t probe[] = {NAT_T_KEEPALIVE_PAYLOAD_BYTE};
  if (!nat_t_keepalive_is_probe(probe, sizeof(probe))) return 1;
  return 0;
}

static int test_non_probe_payloads_are_rejected(void) {
  const uint8_t zero4[] = {0x00, 0x00, 0x00, 0x00};
  const uint8_t ff2[] = {NAT_T_KEEPALIVE_PAYLOAD_BYTE, 0x00};
  if (nat_t_keepalive_is_probe(NULL, 0u)) return 1;
  if (nat_t_keepalive_is_probe(zero4, sizeof(zero4))) return 2;
  if (nat_t_keepalive_is_probe(ff2, sizeof(ff2))) return 3;
  return 0;
}

static int test_write_probe_writes_single_ff(void) {
  uint8_t out[4] = {0x00, 0x00, 0x00, 0x00};
  size_t n = nat_t_keepalive_write_probe(out, sizeof(out));
  if (n != 1u) return 1;
  if (out[0] != NAT_T_KEEPALIVE_PAYLOAD_BYTE) return 2;
  if (out[1] != 0x00 || out[2] != 0x00 || out[3] != 0x00) return 3;
  return 0;
}

static int test_due_uses_outbound_idle_window(void) {
  const time_t last_outbound = 100;
  if (nat_t_keepalive_is_due(114, last_outbound, NAT_T_KEEPALIVE_INTERVAL_SECS)) return 1;
  if (!nat_t_keepalive_is_due(115, last_outbound, NAT_T_KEEPALIVE_INTERVAL_SECS)) return 2;
  if (!nat_t_keepalive_is_due(130, last_outbound, NAT_T_KEEPALIVE_INTERVAL_SECS)) return 3;
  if (nat_t_keepalive_is_due(90, last_outbound, NAT_T_KEEPALIVE_INTERVAL_SECS)) return 4;
  return 0;
}

int main(void) {
  int rc = test_single_byte_ff_is_probe();
  if (rc != 0) {
    fprintf(stderr, "test_single_byte_ff_is_probe failed: %d\n", rc);
    return 1;
  }
  rc = test_non_probe_payloads_are_rejected();
  if (rc != 0) {
    fprintf(stderr, "test_non_probe_payloads_are_rejected failed: %d\n", rc);
    return 1;
  }
  rc = test_write_probe_writes_single_ff();
  if (rc != 0) {
    fprintf(stderr, "test_write_probe_writes_single_ff failed: %d\n", rc);
    return 1;
  }
  rc = test_due_uses_outbound_idle_window();
  if (rc != 0) {
    fprintf(stderr, "test_due_uses_outbound_idle_window failed: %d\n", rc);
    return 1;
  }
  puts("test_nat_t_keepalive: ok");
  return 0;
}
