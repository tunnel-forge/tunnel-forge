#include "ipv4_dataplane.h"

#include "util_endian.h"

int tf_ipv4_parse(const uint8_t *packet, size_t available_len, tf_ipv4_info_t *out) {
  if (packet == 0 || out == 0 || available_len < 20u)
    return -1;
  if ((packet[0] >> 4) != 4u)
    return -1;
  size_t ihl = (size_t)(packet[0] & 0x0fu) * 4u;
  if (ihl < 20u || ihl > available_len)
    return -1;
  uint16_t total_len = util_read_be16(packet + 2u);
  if ((size_t)total_len < ihl || (size_t)total_len > available_len)
    return -1;
  out->ihl = ihl;
  out->total_len = (size_t)total_len;
  out->protocol = packet[9];
  return 0;
}

uint16_t tf_inet_checksum_fold(uint32_t sum) {
  while (sum >> 16) {
    sum = (sum & 0xffffu) + (sum >> 16);
  }
  return (uint16_t)~sum;
}

uint32_t tf_csum_acc_bytes(uint32_t sum, const uint8_t *p, size_t n) {
  for (size_t i = 0; i < n; i += 2) {
    uint32_t w = (uint32_t)p[i] << 8;
    if (i + 1u < n)
      w |= p[i + 1u];
    sum += w;
  }
  return sum;
}

tf_l4_checksum_result_t tf_ipv4_l4_checksum_status(const uint8_t *packet, size_t len, const tf_ipv4_info_t *info) {
  if (packet == 0 || info == 0 || len < info->total_len || info->total_len < info->ihl)
    return TF_L4_CHECKSUM_NOT_CHECKED;
  uint16_t frag = util_read_be16(packet + 6u);
  if ((frag & 0x1fffu) != 0u)
    return TF_L4_CHECKSUM_NOT_CHECKED;
  size_t l4_len = info->total_len - info->ihl;
  const uint8_t *l4 = packet + info->ihl;
  uint16_t checksum = 0u;
  if (info->protocol == TF_IPPROTO_TCP) {
    if (l4_len < 20u)
      return TF_L4_CHECKSUM_NOT_CHECKED;
    checksum = util_read_be16(l4 + 16u);
  } else if (info->protocol == TF_IPPROTO_UDP) {
    if (l4_len < 8u)
      return TF_L4_CHECKSUM_NOT_CHECKED;
    uint16_t udp_len = util_read_be16(l4 + 4u);
    if (udp_len < 8u || (size_t)udp_len > l4_len)
      return TF_L4_CHECKSUM_NOT_CHECKED;
    if (util_read_be16(l4 + 6u) == 0u)
      return TF_L4_CHECKSUM_NOT_CHECKED;
    l4_len = (size_t)udp_len;
    checksum = util_read_be16(l4 + 6u);
  } else {
    return TF_L4_CHECKSUM_NOT_CHECKED;
  }

  if (checksum == 0u)
    return TF_L4_CHECKSUM_NOT_CHECKED;
  uint32_t sum = 0;
  sum = tf_csum_acc_bytes(sum, packet + 12u, 4u);
  sum = tf_csum_acc_bytes(sum, packet + 16u, 4u);
  sum += (uint32_t)info->protocol;
  sum += (uint32_t)l4_len;
  sum = tf_csum_acc_bytes(sum, l4, l4_len);
  return tf_inet_checksum_fold(sum) == 0u ? TF_L4_CHECKSUM_VALID : TF_L4_CHECKSUM_INVALID;
}
