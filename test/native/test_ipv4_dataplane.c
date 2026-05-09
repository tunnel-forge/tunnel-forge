#include "ipv4_dataplane.h"
#include "util_endian.h"

#include <stdio.h>
#include <string.h>

static void build_ipv4_tcp(uint8_t *pkt, size_t len) {
  memset(pkt, 0, len);
  pkt[0] = 0x45u;
  util_write_be16(pkt + 2u, 40u);
  pkt[8] = 64u;
  pkt[9] = TF_IPPROTO_TCP;
  pkt[12] = 10u;
  pkt[15] = 1u;
  pkt[16] = 10u;
  pkt[19] = 2u;
  util_write_be16(pkt + 20u, 12345u);
  util_write_be16(pkt + 22u, 443u);
  pkt[32] = 0x50u;
  pkt[33] = 0x02u;
}

static uint16_t tcp_checksum(const uint8_t *pkt) {
  tf_ipv4_info_t info;
  if (tf_ipv4_parse(pkt, 40u, &info) != 0)
    return 0u;
  uint32_t sum = 0;
  sum = tf_csum_acc_bytes(sum, pkt + 12u, 4u);
  sum = tf_csum_acc_bytes(sum, pkt + 16u, 4u);
  sum += TF_IPPROTO_TCP;
  sum += (uint32_t)(info.total_len - info.ihl);
  sum = tf_csum_acc_bytes(sum, pkt + info.ihl, info.total_len - info.ihl);
  return tf_inet_checksum_fold(sum);
}

static int test_valid_packet_with_trailing_bytes_parses_trim_len(void) {
  uint8_t pkt[44];
  build_ipv4_tcp(pkt, sizeof(pkt));
  tf_ipv4_info_t info;
  if (tf_ipv4_parse(pkt, sizeof(pkt), &info) != 0)
    return 1;
  if (info.ihl != 20u || info.total_len != 40u || info.protocol != TF_IPPROTO_TCP)
    return 2;
  return 0;
}

static int test_malformed_total_length_drops(void) {
  uint8_t pkt[40];
  build_ipv4_tcp(pkt, sizeof(pkt));
  tf_ipv4_info_t info;
  util_write_be16(pkt + 2u, 19u);
  if (tf_ipv4_parse(pkt, sizeof(pkt), &info) == 0)
    return 1;
  util_write_be16(pkt + 2u, 41u);
  if (tf_ipv4_parse(pkt, sizeof(pkt), &info) == 0)
    return 2;
  return 0;
}

static int test_exact_length_packet_unchanged(void) {
  uint8_t pkt[40];
  build_ipv4_tcp(pkt, sizeof(pkt));
  tf_ipv4_info_t info;
  if (tf_ipv4_parse(pkt, sizeof(pkt), &info) != 0)
    return 1;
  return info.total_len == sizeof(pkt) ? 0 : 2;
}

static int test_zero_tcp_checksum_can_be_filled_and_validated(void) {
  uint8_t pkt[40];
  build_ipv4_tcp(pkt, sizeof(pkt));
  util_write_be16(pkt + 36u, tcp_checksum(pkt));
  tf_ipv4_info_t info;
  if (tf_ipv4_parse(pkt, sizeof(pkt), &info) != 0)
    return 1;
  if (tf_ipv4_l4_checksum_status(pkt, sizeof(pkt), &info) != TF_L4_CHECKSUM_VALID)
    return 2;
  return 0;
}

static int test_valid_checksum_remains_valid_and_invalid_is_countable(void) {
  uint8_t pkt[40];
  build_ipv4_tcp(pkt, sizeof(pkt));
  util_write_be16(pkt + 36u, tcp_checksum(pkt));
  tf_ipv4_info_t info;
  if (tf_ipv4_parse(pkt, sizeof(pkt), &info) != 0)
    return 1;
  if (tf_ipv4_l4_checksum_status(pkt, sizeof(pkt), &info) != TF_L4_CHECKSUM_VALID)
    return 2;
  pkt[25] ^= 0x7fu;
  if (tf_ipv4_l4_checksum_status(pkt, sizeof(pkt), &info) != TF_L4_CHECKSUM_INVALID)
    return 3;
  return 0;
}

int main(void) {
  int rc;
  rc = test_valid_packet_with_trailing_bytes_parses_trim_len();
  if (rc != 0) {
    fprintf(stderr, "trailing trim parse failed: %d\n", rc);
    return rc;
  }
  rc = test_malformed_total_length_drops();
  if (rc != 0) {
    fprintf(stderr, "malformed total_length failed: %d\n", rc);
    return rc;
  }
  rc = test_exact_length_packet_unchanged();
  if (rc != 0) {
    fprintf(stderr, "exact length failed: %d\n", rc);
    return rc;
  }
  rc = test_zero_tcp_checksum_can_be_filled_and_validated();
  if (rc != 0) {
    fprintf(stderr, "checksum fill validation failed: %d\n", rc);
    return rc;
  }
  rc = test_valid_checksum_remains_valid_and_invalid_is_countable();
  if (rc != 0) {
    fprintf(stderr, "checksum validity failed: %d\n", rc);
    return rc;
  }
  puts("test_ipv4_dataplane: ok");
  return 0;
}
