#include "../../android/app/src/main/cpp/util_endian.h"

#include <stdint.h>
#include <stdio.h>

#define PROTO_PAP 0xc023u
#define PROTO_LCP 0xc021u

typedef enum {
  PPP_AUTH_PAP = 0,
  PPP_AUTH_MSCHAPV2,
  PPP_AUTH_CHAP_MD5,
} ppp_auth_kind_t;

static int lcp_build_cr(uint8_t *out, size_t cap, uint8_t id, ppp_auth_kind_t auth, uint16_t mru, int include_address_control,
                        int include_accm, int include_auth) {
  size_t o = 0;
  if (include_address_control) {
    if (cap < 12u) return -1;
    out[o++] = 0xff;
    out[o++] = 0x03;
    util_write_be16(out + o, PROTO_LCP);
    o += 2u;
  } else {
    if (cap < 10u) return -1;
    util_write_be16(out + o, PROTO_LCP);
    o += 2u;
  }

  size_t code_off = o;
  out[o++] = 1;
  out[o++] = id;
  size_t len_mark = o;
  o += 2u;

  out[o++] = 1;
  out[o++] = 4;
  util_write_be16(out + o, mru);
  o += 2u;

  if (include_accm) {
    if (o + 6u > cap) return -1;
    out[o++] = 2;
    out[o++] = 6;
    util_write_be32(out + o, 0u);
    o += 4u;
  }

  if (include_auth) {
    out[o++] = 3;
    if (auth == PPP_AUTH_PAP) {
      if (o + 3u > cap) return -1;
      out[o++] = 4;
      util_write_be16(out + o, PROTO_PAP);
      o += 2u;
    } else if (auth == PPP_AUTH_MSCHAPV2) {
      if (o + 4u > cap) return -1;
      out[o++] = 5;
      out[o++] = 0xc2;
      out[o++] = 0x23;
      out[o++] = 0x81;
    } else {
      if (o + 4u > cap) return -1;
      out[o++] = 5;
      out[o++] = 0xc2;
      out[o++] = 0x23;
      out[o++] = 0x05;
    }
  }

  util_write_be16(out + len_mark, (uint16_t)(o - code_off));
  return (int)o;
}

static void lcp_apply_conf_reject(const uint8_t *lcp, size_t lcp_len, int *include_accm, int *include_auth) {
  if (lcp_len < 6u) return;
  const uint8_t *opt = lcp + 4u;
  size_t rem = lcp_len - 4u;
  size_t i = 0;
  while (i + 2u <= rem) {
    uint8_t t = opt[i];
    uint8_t ol = opt[i + 1u];
    if (ol < 2u || i + (size_t)ol > rem) break;
    if (t == 2u && include_accm != NULL && *include_accm) {
      *include_accm = 0;
    } else if (t == 3u && include_auth != NULL && *include_auth) {
      *include_auth = 0;
    }
    i += (size_t)ol;
  }
}

static int lcp_has_option(const uint8_t *ppp, size_t len, uint8_t want_type) {
  size_t p = 0;
  if (len >= 2u && ppp[0] == 0xffu && ppp[1] == 0x03u) p += 2u;
  if (p + 6u > len) return 0;
  if (util_read_be16(ppp + p) != PROTO_LCP) return 0;
  p += 2u;
  uint16_t lcp_len = util_read_be16(ppp + p + 2u);
  if (lcp_len < 4u || p + (size_t)lcp_len > len) return 0;
  size_t i = p + 4u;
  size_t end = p + (size_t)lcp_len;
  while (i + 2u <= end) {
    uint8_t t = ppp[i];
    uint8_t ol = ppp[i + 1u];
    if (ol < 2u || i + (size_t)ol > end) break;
    if (t == want_type) return 1;
    i += (size_t)ol;
  }
  return 0;
}

static int lcp_read_mru(const uint8_t *ppp, size_t len, uint16_t *mru_out) {
  size_t p = 0;
  if (len >= 2u && ppp[0] == 0xffu && ppp[1] == 0x03u) p += 2u;
  if (p + 10u > len) return -1;
  if (util_read_be16(ppp + p) != PROTO_LCP) return -1;
  p += 2u;
  uint16_t lcp_len = util_read_be16(ppp + p + 2u);
  if (lcp_len < 8u || p + (size_t)lcp_len > len) return -1;
  size_t i = p + 4u;
  size_t end = p + (size_t)lcp_len;
  while (i + 4u <= end) {
    uint8_t t = ppp[i];
    uint8_t ol = ppp[i + 1u];
    if (ol < 2u || i + (size_t)ol > end) break;
    if (t == 1u && ol >= 4u) {
      if (mru_out != NULL) *mru_out = util_read_be16(ppp + i + 2u);
      return 0;
    }
    i += (size_t)ol;
  }
  return -1;
}

static int test_selected_mru_is_preserved(void) {
  uint8_t cr[128];
  uint16_t parsed_mru = 0;
  const uint16_t selected_mru = 1450u;

  int n = lcp_build_cr(cr, sizeof(cr), 1, PPP_AUTH_MSCHAPV2, selected_mru, 1, 1, 1);
  if (n <= 0) return 1;
  if (lcp_read_mru(cr, (size_t)n, &parsed_mru) != 0) return 2;
  if (parsed_mru != selected_mru) return 3;

  n = lcp_build_cr(cr, sizeof(cr), 2, PPP_AUTH_CHAP_MD5, selected_mru, 1, 1, 0);
  if (n <= 0) return 4;
  if (lcp_read_mru(cr, (size_t)n, &parsed_mru) != 0) return 5;
  if (parsed_mru != selected_mru) return 6;
  return 0;
}

static int test_reject_accm_and_auth_strips_future_cr(void) {
  uint8_t cr[128];
  int include_accm = 1;
  int include_auth = 1;

  int n = lcp_build_cr(cr, sizeof(cr), 1, PPP_AUTH_MSCHAPV2, 1500, 1, include_accm, include_auth);
  if (n <= 0) return 1;
  if (!lcp_has_option(cr, (size_t)n, 1) || !lcp_has_option(cr, (size_t)n, 2) || !lcp_has_option(cr, (size_t)n, 3)) return 2;

  const uint8_t conf_reject[] = {
      0x04, 0x01, 0x00, 0x0f,
      0x02, 0x06, 0x00, 0x00, 0x00, 0x00,
      0x03, 0x05, 0xc2, 0x23, 0x81,
  };
  lcp_apply_conf_reject(conf_reject, sizeof(conf_reject), &include_accm, &include_auth);
  if (include_accm != 0 || include_auth != 0) return 3;

  n = lcp_build_cr(cr, sizeof(cr), 2, PPP_AUTH_MSCHAPV2, 1500, 1, include_accm, include_auth);
  if (n <= 0) return 4;
  if (!lcp_has_option(cr, (size_t)n, 1)) return 5;
  if (lcp_has_option(cr, (size_t)n, 2)) return 6;
  if (lcp_has_option(cr, (size_t)n, 3)) return 7;
  return 0;
}

static int test_reject_unrelated_option_keeps_accm_and_auth(void) {
  int include_accm = 1;
  int include_auth = 1;
  const uint8_t conf_reject[] = {
      0x04, 0x05, 0x00, 0x08,
      0x05, 0x04, 0x12, 0x34,
  };
  lcp_apply_conf_reject(conf_reject, sizeof(conf_reject), &include_accm, &include_auth);
  if (include_accm != 1 || include_auth != 1) return 1;
  return 0;
}

static int test_small_caps_reject_truncated_lcp_frames(void) {
  uint8_t cr[32];

  if (lcp_build_cr(cr, 11u, 1, PPP_AUTH_PAP, 1500, 1, 0, 0) != -1) return 1;
  if (lcp_build_cr(cr, 9u, 1, PPP_AUTH_PAP, 1500, 0, 0, 0) != -1) return 2;
  if (lcp_build_cr(cr, 17u, 1, PPP_AUTH_PAP, 1500, 1, 1, 0) != -1) return 3;
  if (lcp_build_cr(cr, 15u, 1, PPP_AUTH_PAP, 1500, 0, 1, 0) != -1) return 4;
  if (lcp_build_cr(cr, 15u, 1, PPP_AUTH_PAP, 1500, 1, 0, 1) != -1) return 5;
  if (lcp_build_cr(cr, 16u, 1, PPP_AUTH_MSCHAPV2, 1500, 1, 0, 1) != -1) return 6;
  if (lcp_build_cr(cr, 15u, 1, PPP_AUTH_CHAP_MD5, 1500, 1, 0, 1) != -1) return 7;
  if (lcp_build_cr(cr, 23u, 1, PPP_AUTH_MSCHAPV2, 1500, 1, 1, 1) <= 0) return 8;
  if (lcp_build_cr(cr, 21u, 1, PPP_AUTH_MSCHAPV2, 1500, 0, 1, 1) <= 0) return 9;

  return 0;
}

int main(void) {
  int rc = test_selected_mru_is_preserved();
  if (rc != 0) {
    fprintf(stderr, "test_selected_mru_is_preserved failed: %d\n", rc);
    return 1;
  }
  rc = test_reject_accm_and_auth_strips_future_cr();
  if (rc != 0) {
    fprintf(stderr, "test_reject_accm_and_auth_strips_future_cr failed: %d\n", rc);
    return 1;
  }
  rc = test_reject_unrelated_option_keeps_accm_and_auth();
  if (rc != 0) {
    fprintf(stderr, "test_reject_unrelated_option_keeps_accm_and_auth failed: %d\n", rc);
    return 1;
  }
  rc = test_small_caps_reject_truncated_lcp_frames();
  if (rc != 0) {
    fprintf(stderr, "test_small_caps_reject_truncated_lcp_frames failed: %d\n", rc);
    return 1;
  }
  puts("test_ppp_lcp: ok");
  return 0;
}
