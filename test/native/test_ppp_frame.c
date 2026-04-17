#include <stdint.h>
#include <stdio.h>
#include <string.h>

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
  puts("test_ppp_frame: ok");
  return 0;
}
