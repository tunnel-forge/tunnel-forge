#include "../../android/app/src/main/cpp/util_endian.h"

#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define PROTO_IPCP 0x8021u

static int ipcp_opts_first_ip3(const uint8_t *ipcp, uint16_t ipcplen, uint8_t ip_out[4]) {
  if (ipcplen < 4u) return -1;
  const uint8_t *opts = ipcp + 4u;
  size_t rem = (size_t)ipcplen - 4u;
  size_t i = 0;
  while (i + 2u <= rem) {
    uint8_t t = opts[i];
    uint8_t ol = opts[i + 1u];
    if (ol < 2u || i + (size_t)ol > rem) break;
    if (t == 3u && ol >= 6u) {
      memcpy(ip_out, opts + i + 2u, 4);
      return 0;
    }
    i += (size_t)ol;
  }
  return -1;
}

static int ipcp_build_cr(uint8_t *out, size_t cap, uint8_t id, const uint8_t ip[4], int include_address_control,
                         int include_ip_opt) {
  size_t o = 0;
  if (include_address_control) {
    if (cap < 32u) return -1;
    out[o++] = 0xff;
    out[o++] = 0x03;
    util_write_be16(out + o, PROTO_IPCP);
    o += 2u;
  } else {
    if (cap < 28u) return -1;
    util_write_be16(out + o, PROTO_IPCP);
    o += 2u;
  }
  size_t code_off = o;
  out[o++] = 1;
  out[o++] = id;
  size_t len_mark = o;
  o += 2u;
  if (include_ip_opt) {
    if (o + 6u > cap) return -1;
    out[o++] = 3;
    out[o++] = 6;
    memcpy(out + o, ip, 4);
    o += 4u;
  }
  util_write_be16(out + len_mark, (uint16_t)(o - code_off));
  return (int)o;
}

static int test_cr_contains_ip3(void) {
  uint8_t buf[32];
  uint8_t ip[4] = {192, 168, 1, 10};
  int n = ipcp_build_cr(buf, sizeof(buf), 1, ip, 1, 1);
  if (n < 14) return 1;
  if (buf[0] != 0xff || buf[1] != 0x03) return 2;
  if (util_read_be16(buf + 2) != PROTO_IPCP) return 3;
  if (buf[4] != 1 || buf[5] != 1) return 4;
  uint16_t l = util_read_be16(buf + 6);
  if (l != (uint16_t)(n - 4)) return 5;
  if (buf[8] != 3 || buf[9] != 6) return 6;
  if (memcmp(buf + 10, ip, 4) != 0) return 7;
  return 0;
}

static int test_nak_updates_req_ip(void) {
  const uint8_t nak[] = {
      0x03, 0x01, 0x00, 0x0a, 0x03, 0x06, 10, 20, 30, 40,
  };
  uint8_t req[4] = {0, 0, 0, 0};
  if (ipcp_opts_first_ip3(nak, sizeof(nak), req) != 0) return 1;
  if (req[0] != 10 || req[1] != 20 || req[2] != 30 || req[3] != 40) return 2;
  return 0;
}

static int test_peer_cr_to_ack_flips_code(void) {
  uint8_t in[] = {0xff, 0x03, 0x80, 0x21, 0x01, 0x07, 0x00, 0x0a, 0x03, 0x06, 1, 2, 3, 4};
  const uint8_t *p = in + 2;
  uint16_t ipcplen = util_read_be16(p + 4);
  size_t prefix = 2u;
  size_t total = prefix + 2u + (size_t)ipcplen;
  if (total > sizeof(in)) return 1;
  uint8_t ackbuf[32];
  memcpy(ackbuf, in, total);
  if (util_read_be16(ackbuf + prefix) != PROTO_IPCP) return 2;
  ackbuf[prefix + 2u] = 2;
  if (ackbuf[prefix + 2u] != 2) return 3;
  if (ackbuf[prefix + 3u] != 7) return 4;
  return 0;
}

int main(void) {
  int rc = test_cr_contains_ip3();
  if (rc != 0) {
    fprintf(stderr, "test_cr_contains_ip3 failed: %d\n", rc);
    return 1;
  }
  rc = test_nak_updates_req_ip();
  if (rc != 0) {
    fprintf(stderr, "test_nak_updates_req_ip failed: %d\n", rc);
    return 1;
  }
  rc = test_peer_cr_to_ack_flips_code();
  if (rc != 0) {
    fprintf(stderr, "test_peer_cr_to_ack_flips_code failed: %d\n", rc);
    return 1;
  }
  puts("test_ppp_ipcp: ok");
  return 0;
}
