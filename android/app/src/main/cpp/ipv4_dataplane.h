#ifndef TUNNEL_FORGE_IPV4_DATAPLANE_H
#define TUNNEL_FORGE_IPV4_DATAPLANE_H

#include <stddef.h>
#include <stdint.h>

#define TF_IPPROTO_TCP 6u
#define TF_IPPROTO_UDP 17u

/* Parsed IPv4 header fields needed by PPP and proxy dataplane helpers. */
typedef struct {
  size_t ihl;
  size_t total_len;
  uint8_t protocol;
} tf_ipv4_info_t;

/* L4 checksum state for unfragmented TCP/UDP packets. */
typedef enum {
  TF_L4_CHECKSUM_NOT_CHECKED = 0,
  TF_L4_CHECKSUM_VALID = 1,
  TF_L4_CHECKSUM_INVALID = 2,
} tf_l4_checksum_result_t;

/* Validates the IPv4 header and clamps total_len to the packet's declared IPv4 length. */
int tf_ipv4_parse(const uint8_t *packet, size_t available_len, tf_ipv4_info_t *out);

/* RFC 1071 checksum helpers shared by PPP egress and dataplane validation. */
uint16_t tf_inet_checksum_fold(uint32_t sum);
uint32_t tf_csum_acc_bytes(uint32_t sum, const uint8_t *p, size_t n);

/*
 * Checks TCP/UDP checksums when enough packet context is available. Fragmented or unsupported
 * packets return TF_L4_CHECKSUM_NOT_CHECKED so callers do not treat them as malformed.
 */
tf_l4_checksum_result_t tf_ipv4_l4_checksum_status(const uint8_t *packet, size_t len, const tf_ipv4_info_t *info);

#endif
