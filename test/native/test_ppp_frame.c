#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define PROTO_LCP 0xc021u

/** Mirrors ppp_encapsulate_and_send layout (no Android). */
static size_t ppp_ip_frame_len(size_t ip_len, int lcp_acfc) {
  if (lcp_acfc) return 2u + ip_len;
  return 4u + ip_len;
}

static int build_ppp_ip_frame(uint8_t *out, size_t cap, const uint8_t *ip, size_t ip_len, int lcp_acfc) {
  if (lcp_acfc) {
    if (ip_len + 2u > cap) return -1;
    out[0] = 0x00;
    out[1] = 0x21;
    memcpy(out + 2, ip, ip_len);
    return (int)(2u + ip_len);
  }
  if (ip_len + 4u > cap) return -1;
  out[0] = 0xff;
  out[1] = 0x03;
  out[2] = 0x00;
  out[3] = 0x21;
  memcpy(out + 4, ip, ip_len);
  return (int)(4u + ip_len);
}

static uint16_t read_be16(const uint8_t *p) {
  return (uint16_t)(((uint16_t)p[0] << 8) | p[1]);
}

static void write_be16(uint8_t *p, uint16_t v) {
  p[0] = (uint8_t)(v >> 8);
  p[1] = (uint8_t)(v & 0xffu);
}

static void write_be32(uint8_t *p, uint32_t v) {
  p[0] = (uint8_t)(v >> 24);
  p[1] = (uint8_t)(v >> 16);
  p[2] = (uint8_t)(v >> 8);
  p[3] = (uint8_t)(v & 0xffu);
}

static uint32_t read_be32(const uint8_t *p) {
  return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | (uint32_t)p[3];
}

static int build_lcp_echo_reply(uint8_t *out, size_t cap, const uint8_t *req_lcp, size_t req_len, int lcp_acfc) {
  if (out == NULL || req_lcp == NULL || req_len < 8u) return -1;
  if (req_lcp[0] != 9u) return -1;
  uint16_t lcp_len = read_be16(req_lcp + 2u);
  size_t prefix = lcp_acfc ? 2u : 4u;
  if (lcp_len < 8u || (size_t)lcp_len > req_len || prefix + (size_t)lcp_len > cap) return -1;
  if (lcp_acfc) {
    write_be16(out, PROTO_LCP);
  } else {
    out[0] = 0xffu;
    out[1] = 0x03u;
    write_be16(out + 2u, PROTO_LCP);
  }
  out[prefix + 0u] = 10u;
  out[prefix + 1u] = req_lcp[1];
  write_be16(out + prefix + 2u, lcp_len);
  write_be32(out + prefix + 4u, 0u);
  if (lcp_len > 8u) memcpy(out + prefix + 8u, req_lcp + 8u, (size_t)lcp_len - 8u);
  return (int)(prefix + (size_t)lcp_len);
}

static uint16_t checksum_fold(uint32_t sum) {
  while (sum >> 16) sum = (sum & 0xffffu) + (sum >> 16);
  return (uint16_t)~sum;
}

static uint32_t checksum_acc_bytes(uint32_t sum, const uint8_t *p, size_t n) {
  for (size_t i = 0; i < n; i += 2u) {
    uint32_t w = (uint32_t)p[i] << 8;
    if (i + 1u < n) w |= p[i + 1u];
    sum += w;
  }
  return sum;
}

static int clamp_tcp_syn_mss(uint8_t *pkt, size_t len, uint16_t clamp_mss, uint16_t *old_mss_out,
                             uint16_t *new_mss_out) {
  if (pkt == NULL || len < 20u || (pkt[0] >> 4) != 4u) return 0;
  unsigned ihl = (unsigned)(pkt[0] & 0x0fu) * 4u;
  if (ihl < 20u || ihl > len) return 0;
  uint16_t tot_len = read_be16(pkt + 2u);
  if (tot_len < ihl || tot_len > len) return 0;
  if ((read_be16(pkt + 6u) & 0x3fffu) != 0u) return 0;
  if (pkt[9] != 6u) return 0;

  uint8_t *tcp = pkt + ihl;
  size_t tcp_len = (size_t)tot_len - ihl;
  if (tcp_len < 20u) return 0;
  unsigned tcp_hlen = (unsigned)(tcp[12] >> 4) * 4u;
  if (tcp_hlen < 20u || tcp_hlen > tcp_len) return 0;
  if ((tcp[13] & 0x02u) == 0u) return 0;

  size_t opt = 20u;
  while (opt < tcp_hlen) {
    uint8_t kind = tcp[opt];
    if (kind == 0u) break;
    if (kind == 1u) {
      opt++;
      continue;
    }
    if (opt + 1u >= tcp_hlen) break;
    uint8_t olen = tcp[opt + 1u];
    if (olen < 2u || opt + (size_t)olen > tcp_hlen) break;
    if (kind == 2u && olen == 4u) {
      uint16_t old_mss = read_be16(tcp + opt + 2u);
      if (old_mss > clamp_mss) {
        write_be16(tcp + opt + 2u, clamp_mss);
        write_be16(tcp + 16u, 0u);
        if (old_mss_out != NULL) *old_mss_out = old_mss;
        if (new_mss_out != NULL) *new_mss_out = clamp_mss;
        return 1;
      }
      return 0;
    }
    opt += (size_t)olen;
  }
  return 0;
}

static void recompute_tcp_checksum_if_zero(uint8_t *pkt, size_t len) {
  if (pkt == NULL || len < 40u || (pkt[0] >> 4) != 4u || pkt[9] != 6u) return;
  unsigned ihl = (unsigned)(pkt[0] & 0x0fu) * 4u;
  if (ihl < 20u || ihl > len) return;
  uint16_t tot_len = read_be16(pkt + 2u);
  if (tot_len < ihl || tot_len > len) return;
  uint8_t *tcp = pkt + ihl;
  size_t tcp_len = (size_t)tot_len - ihl;
  if (tcp_len < 20u || read_be16(tcp + 16u) != 0u) return;
  uint32_t sum = 0;
  sum = checksum_acc_bytes(sum, pkt + 12u, 4u);
  sum = checksum_acc_bytes(sum, pkt + 16u, 4u);
  sum += 6u;
  sum += (uint32_t)tcp_len;
  sum = checksum_acc_bytes(sum, tcp, tcp_len);
  write_be16(tcp + 16u, checksum_fold(sum));
}

static void build_ipv4_tcp_syn_with_mss(uint8_t *pkt, uint16_t mss, uint8_t flags, uint16_t checksum) {
  memset(pkt, 0, 44u);
  pkt[0] = 0x45u;
  write_be16(pkt + 2u, 44u);
  pkt[8] = 64u;
  pkt[9] = 6u;
  pkt[12] = 10u;
  pkt[15] = 2u;
  pkt[16] = 10u;
  pkt[19] = 1u;
  write_be16(pkt + 20u, 12345u);
  write_be16(pkt + 22u, 443u);
  pkt[32] = 0x60u;
  pkt[33] = flags;
  write_be16(pkt + 34u, 65535u);
  write_be16(pkt + 36u, checksum);
  pkt[40] = 2u;
  pkt[41] = 4u;
  write_be16(pkt + 42u, mss);
}

static int test_acfc_off_has_ff03_0021(void) {
  uint8_t ip[4] = {0x45, 0x00, 0x00, 0x14};
  uint8_t frame[32];
  int n = build_ppp_ip_frame(frame, sizeof(frame), ip, sizeof(ip), 0);
  if (n != 8) return 1;
  if (frame[0] != 0xff || frame[1] != 0x03 || frame[2] != 0x00 || frame[3] != 0x21) return 2;
  if (memcmp(frame + 4, ip, 4) != 0) return 3;
  if (ppp_ip_frame_len(sizeof(ip), 0) != 8u) return 4;
  return 0;
}

static int test_acfc_on_is_0021_only(void) {
  uint8_t ip[2] = {0x45, 0x00};
  uint8_t frame[16];
  int n = build_ppp_ip_frame(frame, sizeof(frame), ip, sizeof(ip), 1);
  if (n != 4) return 1;
  if (frame[0] != 0x00 || frame[1] != 0x21) return 2;
  if (memcmp(frame + 2, ip, 2) != 0) return 3;
  if (ppp_ip_frame_len(sizeof(ip), 1) != 4u) return 4;
  return 0;
}

static int test_tcp_syn_mss_clamp_updates_option_and_checksum(void) {
  uint8_t pkt[44];
  build_ipv4_tcp_syn_with_mss(pkt, 1460u, 0x02u, 0x1234u);
  uint16_t old_mss = 0u;
  uint16_t new_mss = 0u;
  if (clamp_tcp_syn_mss(pkt, sizeof(pkt), 1410u, &old_mss, &new_mss) != 1) return 1;
  if (old_mss != 1460u || new_mss != 1410u) return 2;
  if (read_be16(pkt + 42u) != 1410u) return 3;
  if (read_be16(pkt + 36u) != 0u) return 4;
  recompute_tcp_checksum_if_zero(pkt, sizeof(pkt));
  if (read_be16(pkt + 36u) == 0u) return 5;
  return 0;
}

static int test_tcp_syn_small_mss_is_unchanged(void) {
  uint8_t pkt[44];
  build_ipv4_tcp_syn_with_mss(pkt, 1380u, 0x02u, 0x2222u);
  if (clamp_tcp_syn_mss(pkt, sizeof(pkt), 1410u, NULL, NULL) != 0) return 1;
  if (read_be16(pkt + 42u) != 1380u) return 2;
  if (read_be16(pkt + 36u) != 0x2222u) return 3;
  return 0;
}

static int test_non_syn_packet_is_unchanged(void) {
  uint8_t pkt[44];
  build_ipv4_tcp_syn_with_mss(pkt, 1460u, 0x10u, 0x3333u);
  if (clamp_tcp_syn_mss(pkt, sizeof(pkt), 1410u, NULL, NULL) != 0) return 1;
  if (read_be16(pkt + 42u) != 1460u) return 2;
  if (read_be16(pkt + 36u) != 0x3333u) return 3;
  return 0;
}

static int test_lcp_echo_reply_zeroes_magic_and_keeps_data(void) {
  const uint8_t req_lcp[] = {
      0x09, 0x42, 0x00, 0x0b,
      0x12, 0x34, 0x56, 0x78,
      0xaa, 0xbb, 0xcc,
  };
  uint8_t reply[32];
  int n = build_lcp_echo_reply(reply, sizeof(reply), req_lcp, sizeof(req_lcp), 0);
  if (n != 15) return 1;
  if (reply[0] != 0xffu || reply[1] != 0x03u) return 2;
  if (read_be16(reply + 2u) != PROTO_LCP) return 3;
  if (reply[4] != 10u || reply[5] != 0x42u) return 4;
  if (read_be16(reply + 6u) != sizeof(req_lcp)) return 5;
  if (read_be32(reply + 8u) != 0u) return 6;
  if (memcmp(reply + 12u, req_lcp + 8u, 3u) != 0) return 7;
  return 0;
}

static int test_lcp_echo_reply_honors_acfc(void) {
  const uint8_t req_lcp[] = {
      0x09, 0x07, 0x00, 0x08,
      0xde, 0xad, 0xbe, 0xef,
  };
  uint8_t reply[32];
  int n = build_lcp_echo_reply(reply, sizeof(reply), req_lcp, sizeof(req_lcp), 1);
  if (n != 10) return 1;
  if (read_be16(reply + 0u) != PROTO_LCP) return 2;
  if (reply[2] != 10u || reply[3] != 0x07u) return 3;
  if (read_be16(reply + 4u) != sizeof(req_lcp)) return 4;
  if (read_be32(reply + 6u) != 0u) return 5;
  return 0;
}

int main(void) {
  int rc = test_acfc_off_has_ff03_0021();
  if (rc != 0) {
    fprintf(stderr, "test_acfc_off_has_ff03_0021 failed: %d\n", rc);
    return 1;
  }
  rc = test_acfc_on_is_0021_only();
  if (rc != 0) {
    fprintf(stderr, "test_acfc_on_is_0021_only failed: %d\n", rc);
    return 1;
  }
  rc = test_tcp_syn_mss_clamp_updates_option_and_checksum();
  if (rc != 0) {
    fprintf(stderr, "test_tcp_syn_mss_clamp_updates_option_and_checksum failed: %d\n", rc);
    return 1;
  }
  rc = test_tcp_syn_small_mss_is_unchanged();
  if (rc != 0) {
    fprintf(stderr, "test_tcp_syn_small_mss_is_unchanged failed: %d\n", rc);
    return 1;
  }
  rc = test_non_syn_packet_is_unchanged();
  if (rc != 0) {
    fprintf(stderr, "test_non_syn_packet_is_unchanged failed: %d\n", rc);
    return 1;
  }
  rc = test_lcp_echo_reply_zeroes_magic_and_keeps_data();
  if (rc != 0) {
    fprintf(stderr, "test_lcp_echo_reply_zeroes_magic_and_keeps_data failed: %d\n", rc);
    return 1;
  }
  rc = test_lcp_echo_reply_honors_acfc();
  if (rc != 0) {
    fprintf(stderr, "test_lcp_echo_reply_honors_acfc failed: %d\n", rc);
    return 1;
  }
  puts("test_ppp_frame: ok");
  return 0;
}
