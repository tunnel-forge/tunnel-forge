/*
 * PPP over L2TP data channel: LCP, authentication (PAP, MS-CHAPv2, CHAP-MD5), IPCP for IPv4,
 * then IP frames (0x0021) to/from the TUN fd. recv_ppp loops on the ESP socket until a PPP frame
 * for the current L2TP session is available.
 */
#include "ppp.h"

#include "engine.h"
#include "esp_udp.h"
#include "ppp_mschap.h"

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <mbedtls/md5.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define LOG_TAG "tunnel_engine"
#define PROTO_IP 0x0021u
#define PROTO_LCP 0xc021u
#define PROTO_PAP 0xc023u
#define PROTO_CHAP 0xc223u
#define PROTO_IPCP 0x8021u
/** IPv6CP (IANA / RFC 5072); gateway may offer after IPv4 IPCP - we send LCP Protocol-Reject. */
#define PROTO_IPV6CP 0x8057u
#define LCP_CODE_PROTOCOL_REJECT 8u
#define LCP_CODE_ECHO_REQUEST 9u
#define LCP_CODE_ECHO_REPLY 10u

typedef enum {
  PPP_AUTH_PAP = 0,
  PPP_AUTH_MSCHAPV2,
  PPP_AUTH_CHAP_MD5,
} ppp_auth_kind_t;

static const char *ppp_auth_name(ppp_auth_kind_t auth) {
  switch (auth) {
    case PPP_AUTH_PAP:
      return "pap";
    case PPP_AUTH_MSCHAPV2:
      return "mschapv2";
    case PPP_AUTH_CHAP_MD5:
      return "chap-md5";
    default:
      return "unknown";
  }
}

static int ppp_read_protocol(const uint8_t *frame, size_t frame_len, uint16_t *proto_out, size_t *proto_len_out) {
  if (frame == NULL || proto_out == NULL || proto_len_out == NULL || frame_len == 0u) return -1;
  if ((frame[0] & 0x01u) != 0u) {
    *proto_out = frame[0];
    *proto_len_out = 1u;
    return 0;
  }
  if (frame_len < 2u) return -1;
  *proto_out = util_read_be16(frame);
  *proto_len_out = 2u;
  return 0;
}

static void ppp_log_rx_summary(const uint8_t *p, size_t len, const char *stage) {
  uint16_t proto = 0u;
  size_t proto_len = 0u;
  if (ppp_read_protocol(p, len, &proto, &proto_len) != 0 || len < proto_len + 2u) return;
  uint8_t code = p[proto_len + 0u];
  uint8_t id = p[proto_len + 1u];
  tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp rx stage=%s proto=0x%04x code=%u id=%u len=%zu", stage,
                    (unsigned)proto, (unsigned)code, (unsigned)id, len);
}

static long long ppp_now_ms(void) {
  struct timeval now;
  gettimeofday(&now, NULL);
  return ((long long)now.tv_sec * 1000ll) + ((long long)now.tv_usec / 1000ll);
}

static int recv_ppp(int esp_fd, esp_keys_t *esp, l2tp_session_t *l2tp, uint8_t *out, size_t cap, int ms) {
  struct timeval start;
  gettimeofday(&start, NULL);
  for (;;) {
    if (tunnel_should_stop()) {
      tunnel_engine_log(ANDROID_LOG_INFO, LOG_TAG, "ppp recv canceled before poll");
      return -1;
    }
    struct timeval now;
    gettimeofday(&now, NULL);
    int elapsed_ms = (int)((now.tv_sec - start.tv_sec) * 1000 + (now.tv_usec - start.tv_usec) / 1000);
    if (elapsed_ms >= ms) {
      tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "ppp recv deadline elapsed=%d budget_ms=%d", elapsed_ms, ms);
      return -1;
    }
    int slice_ms = ms - elapsed_ms;
    if (slice_ms <= 0) return -1;
    struct pollfd pfd = {.fd = esp_fd, .events = POLLIN};
    int waited = 0;
    int pr = 0;
    while (waited < slice_ms) {
      if (tunnel_should_stop()) {
        tunnel_engine_log(ANDROID_LOG_INFO, LOG_TAG, "ppp recv canceled while waiting for frame");
        return -1;
      }
      int poll_slice_ms = slice_ms - waited;
      if (poll_slice_ms > 200) poll_slice_ms = 200;
      pr = poll(&pfd, 1, poll_slice_ms);
      waited += poll_slice_ms;
      if (pr != 0) break;
    }
    if (pr <= 0) {
      if (pr < 0 && errno == EINTR && tunnel_should_stop()) {
        tunnel_engine_log(ANDROID_LOG_INFO, LOG_TAG, "ppp recv canceled after poll interruption");
        return -1;
      }
      tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "ppp recv timeout/poll failure slice_ms=%d pr=%d", slice_ms, pr);
      return -1;
    }

    uint8_t raw[4096];
    ssize_t n = recv(esp_fd, raw, sizeof(raw), 0);
    if (n <= 0) {
      tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "ppp recv socket failure n=%zd errno=%d", n, errno);
      return -1;
    }

    uint8_t plain[4096];
    size_t plen = sizeof(plain);
    if (esp->enc_key_len == 0) {
      if ((size_t)n > plen) continue;
      memcpy(plain, raw, (size_t)n);
      plen = (size_t)n;
    } else {
      if (esp_try_decrypt(esp, raw, (size_t)n, plain, &plen) != 0) {
        char detail[160];
        esp_decrypt_last_fail_snprint(detail, sizeof(detail));
        tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "ppp recv decrypt failed raw_len=%zd %s", n, detail);
        continue;
      }
    }

    if (plen < 6u) {
      tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "ppp recv short decap plen=%zu", plen);
      continue;
    }
    const uint8_t *ppp_ptr = NULL;
    size_t ppp_len = 0;
    if (l2tp_data_extract_ppp(plain, plen, l2tp, &ppp_ptr, &ppp_len, 0) != 0) {
      uint16_t flags = util_read_be16(plain + 0);
      tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "ppp recv l2tp not-ppp-data flags=0x%04x plen=%zu", (unsigned)flags,
                        plen);
      continue;
    }
    if (ppp_len > cap) return -1;
    memcpy(out, ppp_ptr, ppp_len);
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp recv payload ok len=%zu", ppp_len);
    return (int)ppp_len;
  }
}

static int send_ppp(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len, l2tp_session_t *l2tp,
                    const uint8_t *ppp, size_t len) {
  return l2tp_send_ppp(esp_fd, esp, peer, peer_len, l2tp, ppp, len);
}

static void ppp_strip(const uint8_t *frame, size_t frame_len, const uint8_t **body, size_t *body_len) {
  *body = frame;
  *body_len = frame_len;
  if (*body_len >= 2 && (*body)[0] == 0xff && (*body)[1] == 0x03) {
    *body += 2;
    *body_len -= 2;
  }
}

static int urandom_bytes(uint8_t *b, size_t n) {
  int fd = open("/dev/urandom", O_RDONLY);
  if (fd < 0) return -1;
  ssize_t r = read(fd, b, n);
  close(fd);
  return r == (ssize_t)n ? 0 : -1;
}

static int lcp_build_cr(uint8_t *out, size_t cap, uint8_t id, ppp_auth_kind_t auth, uint16_t mru, int include_address_control,
                        int include_accm, int include_auth) {
  size_t o = 0;
  if (include_address_control) {
    if (cap < 40) return -1;
    out[o++] = 0xff;
    out[o++] = 0x03;
    util_write_be16(out + o, PROTO_LCP);
    o += 2;
  } else {
    /* Peer LCP over L2TP often omits Address/Control (ACFC); runtime logs showed c0 21... then silence after
     * our aligned CR still used ff 03. */
    if (cap < 36) return -1;
    util_write_be16(out + o, PROTO_LCP);
    o += 2;
  }
  size_t code_off = o;
  out[o++] = 1;
  out[o++] = id;
  size_t len_mark = o;
  o += 2;
  out[o++] = 1;
  out[o++] = 4;
  util_write_be16(out + o, mru);
  o += 2;
  if (include_accm) {
    /* ACCM 00 00 00 00: keep this unless peer explicitly Configure-Rejects it. */
    if (o > cap || cap - o < 6u) return -1;
    out[o++] = 2;
    out[o++] = 6;
    util_write_be32(out + o, 0u);
    o += 4u;
  }
  if (include_auth) {
    out[o++] = 3;
    if (auth == PPP_AUTH_PAP) {
      if (o > cap || cap - o < 3u) return -1;
      out[o++] = 4;
      util_write_be16(out + o, PROTO_PAP);
      o += 2;
    } else if (auth == PPP_AUTH_MSCHAPV2) {
      if (o > cap || cap - o < 4u) return -1;
      out[o++] = 5;
      out[o++] = 0xc2;
      out[o++] = 0x23;
      out[o++] = 0x81;
    } else {
      /* RFC 1994 CHAP: Authentication-Protocol 0xc223 + algorithm 0x05 (MD5-Challenge). */
      if (o > cap || cap - o < 4u) return -1;
      out[o++] = 5;
      out[o++] = 0xc2;
      out[o++] = 0x23;
      out[o++] = 0x05;
    }
  }
  util_write_be16(out + len_mark, (uint16_t)(o - code_off));
  return (int)o;
}

/* Read Authentication-Protocol from peer LCP Configure-Request ([lcp] = first byte is code). */
static int lcp_parse_peer_auth(const uint8_t *lcp, size_t lcp_len, ppp_auth_kind_t *out) {
  if (lcp_len < 6u || out == NULL) return -1;
  const uint8_t *opts = lcp + 4u;
  size_t rem = lcp_len - 4u;
  size_t i = 0;
  while (i + 2u <= rem) {
    uint8_t t = opts[i];
    uint8_t ol = opts[i + 1u];
    if (ol < 2u || i + (size_t)ol > rem) break;
    if (t == 3u && ol >= 4u) {
      uint16_t proto = ((uint16_t)opts[i + 2u] << 8) | opts[i + 3u];
      if (proto == PROTO_PAP) {
        *out = PPP_AUTH_PAP;
        return 0;
      }
      if (proto == 0xc223u && ol >= 5u) {
        uint8_t algo = opts[i + 4u];
        if (algo == 0x81u) {
          *out = PPP_AUTH_MSCHAPV2;
          return 0;
        }
        *out = PPP_AUTH_CHAP_MD5;
        return 0;
      }
    }
    i += (size_t)ol;
  }
  return -1;
}

static int lcp_parse_peer_mru(const uint8_t *lcp, size_t lcp_len, uint16_t *mru_out) {
  if (lcp_len < 6u || mru_out == NULL) return -1;
  const uint8_t *opts = lcp + 4u;
  size_t rem = lcp_len - 4u;
  size_t i = 0;
  while (i + 2u <= rem) {
    uint8_t t = opts[i];
    uint8_t ol = opts[i + 1u];
    if (ol < 2u || i + (size_t)ol > rem) break;
    if (t == 1u && ol >= 4u) {
      *mru_out = ((uint16_t)opts[i + 2u] << 8) | opts[i + 3u];
      return 0;
    }
    i += (size_t)ol;
  }
  return -1;
}

static int lcp_nak_wants_pap(const uint8_t *opt, size_t opt_len) {
  size_t i = 0;
  while (i + 2 <= opt_len) {
    uint8_t t = opt[i];
    uint8_t l = opt[i + 1];
    if (l < 2 || i + l > opt_len) break;
    if (t == 3 && l >= 4 && opt[i + 2] == 0xc0 && opt[i + 3] == 0x23) return 1;
    i += l;
  }
  return 0;
}

/* Applies RFC1661 Configure-Reject payload to our outgoing Configure-Request option set. */
/** Peer LCP Configure-Request: option type 8 = Address-and-Control-Field-Compression (RFC 1661). */
static void lcp_peer_cr_note_acfc(const uint8_t *lcp, size_t lcp_len, int *lcp_acfc_out) {
  if (lcp_len < 6u || lcp_acfc_out == NULL) return;
  const uint8_t *opts = lcp + 4u;
  size_t rem = lcp_len - 4u;
  size_t i = 0;
  while (i + 2u <= rem) {
    uint8_t t = opts[i];
    uint8_t ol = opts[i + 1u];
    if (ol < 2u || i + (size_t)ol > rem) break;
    if (t == 8u) {
      *lcp_acfc_out = 1;
      return;
    }
    i += (size_t)ol;
  }
}

/** Peer LCP Configure-Request: option type 7 = Protocol-Field-Compression (RFC 1661). */
static void lcp_peer_cr_note_pfc(const uint8_t *lcp, size_t lcp_len, int *lcp_pfc_out) {
  if (lcp_len < 6u || lcp_pfc_out == NULL) return;
  const uint8_t *opts = lcp + 4u;
  size_t rem = lcp_len - 4u;
  size_t i = 0;
  while (i + 2u <= rem) {
    uint8_t t = opts[i];
    uint8_t ol = opts[i + 1u];
    if (ol < 2u || i + (size_t)ol > rem) break;
    if (t == 7u) {
      *lcp_pfc_out = 1;
      return;
    }
    i += (size_t)ol;
  }
}

static void lcp_apply_conf_reject(const uint8_t *lcp, size_t lcp_len, int *include_accm, int *include_auth, int *removed_accm,
                                  int *removed_auth) {
  if (removed_accm != NULL) *removed_accm = 0;
  if (removed_auth != NULL) *removed_auth = 0;
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
      if (removed_accm != NULL) *removed_accm = 1;
    } else if (t == 3u && include_auth != NULL && *include_auth) {
      *include_auth = 0;
      if (removed_auth != NULL) *removed_auth = 1;
    }
    i += (size_t)ol;
  }
}

static int ppp_lcp_negotiate(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len,
                             l2tp_session_t *l2tp, ppp_auth_kind_t *auth_out, ppp_session_t *ppp) {
  uint8_t id = 1;
  ppp_auth_kind_t auth = PPP_AUTH_MSCHAPV2;
  uint8_t pkt[64];
  uint16_t cr_mru = (ppp != NULL && ppp->link_mtu >= 576u) ? ppp->link_mtu : 1500u;
  int lcp_out_include_ac = 1;
  int lcp_peer_framing_seen = 0;
  int lcp_accm_in_cr = 1;
  int lcp_auth_in_cr = 1;
  int plen = lcp_build_cr(pkt, sizeof(pkt), id, auth, cr_mru, 1, lcp_accm_in_cr, lcp_auth_in_cr);
  if (plen < 0) return -1;
  tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp lcp: send Configure-Request id=%u auth=%s mru=%u", (unsigned)id,
                    ppp_auth_name(auth), (unsigned)cr_mru);
  if (send_ppp(esp_fd, esp, peer, peer_len, l2tp, pkt, (size_t)plen) < 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "ppp lcp: send Configure-Request failed id=%u", (unsigned)id);
    return -1;
  }

  uint8_t in[4096];
  int got_ack = 0;
  int peer_cr_hex_done = 0;
  int applied_peer_lcp_auth_hint = 0;
  int pending_resend_cr_after_ack = 0;
  for (int round = 0; round < 16 && !got_ack; round++) {
    int n = recv_ppp(esp_fd, esp, l2tp, in, sizeof(in), 4000);
    if (n < 8) {
      tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "ppp lcp: recv below minimum n=%d round=%d", n, round);
      continue;
    }
    const uint8_t *p = in;
    size_t len = (size_t)n;
    ppp_strip(in, (size_t)n, &p, &len);
    if (len < 6 || util_read_be16(p) != PROTO_LCP) continue;
    ppp_log_rx_summary(p, len, "lcp");
    uint8_t code = p[2];
    uint8_t rid = p[3];
    if (code == 1) {
      if (!lcp_peer_framing_seen) {
        lcp_peer_framing_seen = 1;
        lcp_out_include_ac = (in[0] == 0xff && in[1] == 0x03) ? 1 : 0;
        tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp lcp peer raw addr_ctl=%d in0=%02x in1=%02x", lcp_out_include_ac,
                          in[0], in[1]);
      }
      if (!peer_cr_hex_done) {
        peer_cr_hex_done = 1;
        char hx[80];
        size_t lim = len < 22u ? len : 22u;
        for (size_t zi = 0; zi < lim; zi++) snprintf(hx + zi * 3, 4, "%02x ", p[zi]);
        tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp lcp peer-cr first-bytes len=%zu lim=%zu: %s", len, lim, hx);
      }
      uint8_t ackbuf[256];
      uint16_t lcp_len = util_read_be16(p + 4);
      if (lcp_len < 4u || 2u + (size_t)lcp_len > len) continue;
      if (ppp != NULL) {
        lcp_peer_cr_note_acfc(p + 2u, (size_t)lcp_len, &ppp->lcp_acfc);
        lcp_peer_cr_note_pfc(p + 2u, (size_t)lcp_len, &ppp->lcp_pfc);
      }
      if (!applied_peer_lcp_auth_hint) {
        ppp_auth_kind_t peer_auth = auth;
        if (lcp_parse_peer_auth(p + 2u, (size_t)lcp_len, &peer_auth) == 0) {
          applied_peer_lcp_auth_hint = 1;
          if (peer_auth != auth) {
            auth = peer_auth;
            id++;
            uint16_t peer_mru = 0;
            (void)lcp_parse_peer_mru(p + 2u, (size_t)lcp_len, &peer_mru);
            // Observed Configure-Reject body `03 05 c2 23 05` (CHAP) with MRU+ACCM+CHAP CR: omit
            // Authentication-Protocol from our CR; peer already proposed CHAP in their Configure-Request.
            lcp_auth_in_cr = 0;
            plen = lcp_build_cr(pkt, sizeof(pkt), id, auth, cr_mru, lcp_out_include_ac, lcp_accm_in_cr, lcp_auth_in_cr);
            if (plen < 0) return -1;
            pending_resend_cr_after_ack = 1;
            tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                              "ppp lcp: align auth to peer want=%s peer_mru=%u keep_mru=%u new Configure-Request id=%u auth_in_cr=%d",
                              ppp_auth_name(auth), (unsigned)peer_mru, (unsigned)cr_mru, (unsigned)id,
                              lcp_auth_in_cr);
          }
        }
      }
      size_t ack_ppp_len = 2u + (size_t)lcp_len;
      size_t prefix = (size_t)(p - in);
      size_t total_ack = prefix + ack_ppp_len;
      if (total_ack > (size_t)n || total_ack > sizeof(ackbuf)) continue;
      memcpy(ackbuf, in, total_ack);
      if (util_read_be16(ackbuf + prefix) != PROTO_LCP) continue;
      ackbuf[prefix + 2u] = 2;
      if (send_ppp(esp_fd, esp, peer, peer_len, l2tp, ackbuf, total_ack) < 0) return -1;
      if (pending_resend_cr_after_ack) {
        pending_resend_cr_after_ack = 0;
        if (send_ppp(esp_fd, esp, peer, peer_len, l2tp, pkt, (size_t)plen) < 0) return -1;
        tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp lcp: sent aligned Configure-Request id=%u bytes=%d",
                          (unsigned)id, plen);
      }
      continue;
    }
    if (code == 2 && rid == id) {
      got_ack = 1;
      break;
    }
    if (code == 4 && rid == id) {
      /* Configure-Reject: peer refuses option(s) from our Configure-Request (e.g. Magic/ACCM not wanted). */
      {
        char hx[128];
        size_t lim = len < 40u ? len : 40u;
        for (size_t zi = 0; zi < lim; zi++) snprintf(hx + zi * 3, 4, "%02x ", p[zi]);
        tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG,
                          "ppp lcp: Configure-Reject id=%u len=%zu hex_prefix=%s", (unsigned)rid, len, hx);
      }
      uint16_t lcp_len = util_read_be16(p + 4);
      if (lcp_len >= 4u && 2u + (size_t)lcp_len <= len) {
        int removed_accm = 0;
        int removed_auth = 0;
        lcp_apply_conf_reject(p + 2u, (size_t)lcp_len, &lcp_accm_in_cr, &lcp_auth_in_cr, &removed_accm, &removed_auth);
        if (removed_accm || removed_auth) {
          tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                            "ppp lcp: Configure-Reject removed opts accm=%d auth=%d (next CR accm=%d auth=%d)",
                            removed_accm, removed_auth, lcp_accm_in_cr, lcp_auth_in_cr);
        }
      }
      id++;
      plen = lcp_build_cr(pkt, sizeof(pkt), id, auth, cr_mru, lcp_out_include_ac, lcp_accm_in_cr, lcp_auth_in_cr);
      if (plen < 0) return -1;
      tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp lcp: resend after Configure-Reject id=%u bytes=%d",
                        (unsigned)id, plen);
      if (send_ppp(esp_fd, esp, peer, peer_len, l2tp, pkt, (size_t)plen) < 0) return -1;
      continue;
    }
    if (code == 3 && rid == id) {
      uint16_t lcp_len = util_read_be16(p + 4);
      if (lcp_len >= 6 && (size_t)lcp_len <= len) {
        if (lcp_nak_wants_pap(p + 6, (size_t)lcp_len - 6)) {
          auth = PPP_AUTH_PAP;
        } else {
          auth = PPP_AUTH_CHAP_MD5;
        }
      } else {
        auth = PPP_AUTH_CHAP_MD5;
      }
      id++;
      plen = lcp_build_cr(pkt, sizeof(pkt), id, auth, cr_mru, lcp_out_include_ac, lcp_accm_in_cr, lcp_auth_in_cr);
      if (plen < 0) return -1;
      tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp lcp: received NAK, resend Configure-Request id=%u auth=%s",
                        (unsigned)id, ppp_auth_name(auth));
      if (send_ppp(esp_fd, esp, peer, peer_len, l2tp, pkt, (size_t)plen) < 0) return -1;
    }
  }
  if (!got_ack) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "ppp: LCP timeout");
    return -1;
  }
  tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp lcp: acked final auth=%s acfc=%d pfc=%d mru=%u",
                    ppp_auth_name(auth), ppp != NULL ? ppp->lcp_acfc : -1, ppp != NULL ? ppp->lcp_pfc : -1,
                    (unsigned)cr_mru);
  *auth_out = auth;
  return 0;
}

static int chap_md5_response(const uint8_t chap_id, const char *password, const uint8_t *chal, size_t chal_len,
                             uint8_t hash16[16]) {
  unsigned char dig[16];
  mbedtls_md5_context ctx;
  mbedtls_md5_init(&ctx);
  if (mbedtls_md5_starts_ret(&ctx) != 0) {
    mbedtls_md5_free(&ctx);
    return -1;
  }
  if (mbedtls_md5_update_ret(&ctx, &chap_id, 1) != 0) {
    mbedtls_md5_free(&ctx);
    return -1;
  }
  if (mbedtls_md5_update_ret(&ctx, (const unsigned char *)password, strlen(password)) != 0) {
    mbedtls_md5_free(&ctx);
    return -1;
  }
  if (mbedtls_md5_update_ret(&ctx, chal, chal_len) != 0) {
    mbedtls_md5_free(&ctx);
    return -1;
  }
  if (mbedtls_md5_finish_ret(&ctx, dig) != 0) {
    mbedtls_md5_free(&ctx);
    return -1;
  }
  mbedtls_md5_free(&ctx);
  memcpy(hash16, dig, 16);
  return 0;
}

static int ppp_auth_mschapv2(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len,
                             l2tp_session_t *l2tp, const char *user, const char *password) {
  uint8_t in[4096];
  tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp auth: start mschapv2");
  for (int i = 0; i < 16; i++) {
    int n = recv_ppp(esp_fd, esp, l2tp, in, sizeof(in), 5000);
    if (n < 10) continue;
    const uint8_t *p = in;
    size_t len = (size_t)n;
    ppp_strip(in, (size_t)n, &p, &len);
    if (len < 8 || util_read_be16(p) != PROTO_CHAP || p[2] != 1) continue;
    ppp_log_rx_summary(p, len, "auth-mschapv2-challenge");
    uint8_t chap_id = p[3];
    uint16_t plen = util_read_be16(p + 4);
    if (plen < 8 || plen > len) continue;
    uint8_t vs = p[6];
    if (vs != 16 || (size_t)plen < 7u + vs) continue;
    const uint8_t *auth_chal = p + 7;
    uint8_t peer_chal[16];
    if (urandom_bytes(peer_chal, sizeof(peer_chal)) != 0) return -1;
    uint8_t val49[49];
    if (ppp_mschapv2_response_value(user, password, auth_chal, peer_chal, val49) != 0) return -1;

    uint8_t out[512];
    size_t o = 0;
    out[o++] = 0xff;
    out[o++] = 0x03;
    util_write_be16(out + o, PROTO_CHAP);
    o += 2;
    size_t chap_start = o;
    out[o++] = 2;
    out[o++] = chap_id;
    size_t lenpos = o;
    o += 2;
    out[o++] = 49;
    memcpy(out + o, val49, 49);
    o += 49;
    size_t ulen = strlen(user);
    memcpy(out + o, user, ulen);
    o += ulen;
    util_write_be16(out + lenpos, (uint16_t)(o - chap_start));
    if (send_ppp(esp_fd, esp, peer, peer_len, l2tp, out, o) < 0) return -1;

    for (int j = 0; j < 16; j++) {
      n = recv_ppp(esp_fd, esp, l2tp, in, sizeof(in), 5000);
      if (n < 8) continue;
      ppp_strip(in, (size_t)n, &p, &len);
      if (len >= 4 && util_read_be16(p) == PROTO_CHAP && p[2] == 3) {
        ppp_log_rx_summary(p, len, "auth-mschapv2-success");
        return 0;
      }
      if (len >= 4 && util_read_be16(p) == PROTO_CHAP && p[2] == 4) {
        uint16_t chap_len = len >= 6 ? util_read_be16(p + 4) : 0u;
        ppp_mschapv2_failure_info_t failure;
        if (chap_len >= 4u && (size_t)chap_len + 2u <= len &&
            ppp_mschapv2_parse_failure(p + 6, (size_t)chap_len - 4u, &failure) == 0) {
          char err[16];
          char retry[16];
          char version[16];
          snprintf(err, sizeof(err), failure.has_error_code ? "%d" : "-", failure.error_code);
          snprintf(retry, sizeof(retry), failure.has_retry ? "%d" : "-", failure.retry);
          snprintf(version, sizeof(version), failure.has_version ? "%d" : "-", failure.version);
          tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG,
                            "ppp: MS-CHAPv2 failure E=%s R=%s V=%s M=%s", err, retry, version,
                            failure.has_message ? failure.message : "-");
        } else {
          tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "ppp: MS-CHAPv2 failure");
        }
        return -1;
      }
    }
    return -1;
  }
  return -1;
}

static int ppp_auth_chap_md5(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len,
                             l2tp_session_t *l2tp, const char *user, const char *password) {
  uint8_t in[4096];
  tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp auth: start chap-md5");
  for (int i = 0; i < 16; i++) {
    int n = recv_ppp(esp_fd, esp, l2tp, in, sizeof(in), 5000);
    if (n < 10) continue;
    const uint8_t *p = in;
    size_t len = (size_t)n;
    ppp_strip(in, (size_t)n, &p, &len);
    if (len < 8 || util_read_be16(p) != PROTO_CHAP || p[2] != 1) continue;
    ppp_log_rx_summary(p, len, "auth-chap-md5-challenge");
    uint8_t chap_id = p[3];
    uint16_t plen = util_read_be16(p + 4);
    if (plen < 8 || plen > len) continue;
    uint8_t vs = p[6];
    if (vs == 0 || (size_t)plen < 7u + vs) continue;
    const uint8_t *chal = p + 7;
    uint8_t md[16];
    if (chap_md5_response(chap_id, password, chal, vs, md) != 0) return -1;

    uint8_t out[256];
    size_t o = 0;
    out[o++] = 0xff;
    out[o++] = 0x03;
    util_write_be16(out + o, PROTO_CHAP);
    o += 2;
    size_t chap_start = o;
    out[o++] = 2;
    out[o++] = chap_id;
    size_t lenpos = o;
    o += 2;
    out[o++] = 16;
    memcpy(out + o, md, 16);
    o += 16;
    size_t ulen = strlen(user);
    memcpy(out + o, user, ulen);
    o += ulen;
    util_write_be16(out + lenpos, (uint16_t)(o - chap_start));
    if (send_ppp(esp_fd, esp, peer, peer_len, l2tp, out, o) < 0) return -1;

    for (int j = 0; j < 16; j++) {
      n = recv_ppp(esp_fd, esp, l2tp, in, sizeof(in), 5000);
      if (n < 8) continue;
      ppp_strip(in, (size_t)n, &p, &len);
      if (len >= 4 && util_read_be16(p) == PROTO_CHAP && p[2] == 3) {
        ppp_log_rx_summary(p, len, "auth-chap-md5-success");
        return 0;
      }
      if (len >= 4 && util_read_be16(p) == PROTO_CHAP && p[2] == 4) {
        ppp_log_rx_summary(p, len, "auth-chap-md5-failure");
        return -1;
      }
    }
    return -1;
  }
  return -1;
}

static int ppp_auth_pap(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len,
                        l2tp_session_t *l2tp, const char *user, const char *password) {
  uint8_t pap[128];
  size_t o = 0;
  pap[o++] = 0xff;
  pap[o++] = 0x03;
  util_write_be16(pap + o, PROTO_PAP);
  o += 2;
  size_t codepos = o;
  pap[o++] = 1;
  pap[o++] = 1;
  size_t lenpos = o;
  o += 2;
  size_t ulen = strlen(user);
  size_t pwlen = strlen(password);
  pap[o++] = (uint8_t)ulen;
  memcpy(pap + o, user, ulen);
  o += ulen;
  pap[o++] = (uint8_t)pwlen;
  memcpy(pap + o, password, pwlen);
  o += pwlen;
  util_write_be16(pap + lenpos, (uint16_t)(o - codepos));
  tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp auth: start pap");
  if (send_ppp(esp_fd, esp, peer, peer_len, l2tp, pap, o) < 0) return -1;

  uint8_t in[4096];
  for (int i = 0; i < 12; i++) {
    int n = recv_ppp(esp_fd, esp, l2tp, in, sizeof(in), 5000);
    if (n < 8) continue;
    const uint8_t *p = in;
    size_t len = (size_t)n;
    ppp_strip(in, (size_t)n, &p, &len);
    if (len >= 4 && util_read_be16(p) == PROTO_PAP && p[2] == 2) {
      ppp_log_rx_summary(p, len, "auth-pap-ack");
      return 0;
    }
  }
  return -1;
}

static int ipcp_ipv4_is_zero(const uint8_t ip[4]) { return ip[0] == 0 && ip[1] == 0 && ip[2] == 0 && ip[3] == 0; }

/** [ipcp] starts at IPCP Code byte; [ipcplen] is RFC 1661 Length field (Code+Id+Len+Options). */
#define IPCP_OPT_IP_ADDRESS 3u
#define IPCP_OPT_PRIMARY_DNS 129u
#define IPCP_OPT_SECONDARY_DNS 131u

static int ipcp_opts_first_ipv4_option(const uint8_t *ipcp, uint16_t ipcplen, uint8_t option_type, uint8_t ip_out[4]) {
  if (ipcplen < 4u) return -1;
  const uint8_t *opts = ipcp + 4u;
  size_t rem = (size_t)ipcplen - 4u;
  size_t i = 0;
  while (i + 2u <= rem) {
    uint8_t t = opts[i];
    uint8_t ol = opts[i + 1u];
    if (ol < 2u || i + (size_t)ol > rem) break;
    if (t == option_type && ol >= 6u) {
      memcpy(ip_out, opts + i + 2u, 4);
      return 0;
    }
    i += (size_t)ol;
  }
  return -1;
}

static void ipcp_apply_conf_reject_opts(const uint8_t *ipcp, uint16_t ipcplen, int *include_ip_opt,
                                        int *include_primary_dns_opt, int *include_secondary_dns_opt) {
  if (ipcplen < 4u) return;
  const uint8_t *opts = ipcp + 4u;
  size_t rem = (size_t)ipcplen - 4u;
  size_t i = 0;
  while (i + 2u <= rem) {
    uint8_t t = opts[i];
    uint8_t ol = opts[i + 1u];
    if (ol < 2u || i + (size_t)ol > rem) break;
    if (t == IPCP_OPT_IP_ADDRESS && include_ip_opt != NULL) *include_ip_opt = 0;
    if (t == IPCP_OPT_PRIMARY_DNS && include_primary_dns_opt != NULL) *include_primary_dns_opt = 0;
    if (t == IPCP_OPT_SECONDARY_DNS && include_secondary_dns_opt != NULL) *include_secondary_dns_opt = 0;
    i += (size_t)ol;
  }
}

static int ipcp_build_cr(uint8_t *out, size_t cap, uint8_t id, const uint8_t ip[4], int include_address_control,
                         int include_ip_opt, const uint8_t primary_dns[4], int include_primary_dns_opt,
                         const uint8_t secondary_dns[4], int include_secondary_dns_opt) {
  size_t o = 0;
  if (include_address_control) {
    if (cap < 8u) return -1;
    out[o++] = 0xff;
    out[o++] = 0x03;
    util_write_be16(out + o, PROTO_IPCP);
    o += 2u;
  } else {
    if (cap < 6u) return -1;
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
    out[o++] = IPCP_OPT_IP_ADDRESS;
    out[o++] = 6;
    memcpy(out + o, ip, 4);
    o += 4u;
  }
  if (include_primary_dns_opt) {
    if (o + 6u > cap) return -1;
    out[o++] = IPCP_OPT_PRIMARY_DNS;
    out[o++] = 6;
    memcpy(out + o, primary_dns, 4);
    o += 4u;
  }
  if (include_secondary_dns_opt) {
    if (o + 6u > cap) return -1;
    out[o++] = IPCP_OPT_SECONDARY_DNS;
    out[o++] = 6;
    memcpy(out + o, secondary_dns, 4);
    o += 4u;
  }
  util_write_be16(out + len_mark, (uint16_t)(o - code_off));
  return (int)o;
}

static int ppp_ipcp_negotiate(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len,
                              l2tp_session_t *l2tp, ppp_session_t *ppp) {
  const long long resend_after_ms = 1500ll;
  uint8_t id = 1;
  uint8_t req_ip[4] = {0, 0, 0, 0};
  uint8_t req_primary_dns[4] = {0, 0, 0, 0};
  uint8_t req_secondary_dns[4] = {0, 0, 0, 0};
  int ipcp_out_include_ac = 1;
  int ipcp_peer_framing_seen = 0;
  int include_ip_opt = 1;
  int include_primary_dns_opt = 1;
  int include_secondary_dns_opt = 1;
  uint8_t pkt[64];
  int plen = ipcp_build_cr(pkt, sizeof(pkt), id, req_ip, ipcp_out_include_ac, include_ip_opt, req_primary_dns,
                           include_primary_dns_opt, req_secondary_dns, include_secondary_dns_opt);
  if (plen < 0) return -1;
  tunnel_engine_log(
      ANDROID_LOG_DEBUG, LOG_TAG,
      "ppp ipcp: send Configure-Request id=%u ip=%u.%u.%u.%u primary_dns=%u.%u.%u.%u secondary_dns=%u.%u.%u.%u",
      (unsigned)id, (unsigned)req_ip[0], (unsigned)req_ip[1], (unsigned)req_ip[2], (unsigned)req_ip[3],
      (unsigned)req_primary_dns[0], (unsigned)req_primary_dns[1], (unsigned)req_primary_dns[2],
      (unsigned)req_primary_dns[3], (unsigned)req_secondary_dns[0], (unsigned)req_secondary_dns[1],
      (unsigned)req_secondary_dns[2], (unsigned)req_secondary_dns[3]);
  if (send_ppp(esp_fd, esp, peer, peer_len, l2tp, pkt, (size_t)plen) < 0) return -1;
  long long last_cr_send_ms = ppp_now_ms();

  uint8_t in[4096];
  int got_ack = 0;
  for (int round = 0; round < 32 && !got_ack; round++) {
    int n = recv_ppp(esp_fd, esp, l2tp, in, sizeof(in), 5000);
    if (n < 8) {
      tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "ppp ipcp: recv short/timeout n=%d round=%d", n, round);
      if (ppp_now_ms() - last_cr_send_ms >= resend_after_ms) {
        tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                          "ppp ipcp: resend current Configure-Request id=%u reason=timeout", (unsigned)id);
        if (send_ppp(esp_fd, esp, peer, peer_len, l2tp, pkt, (size_t)plen) < 0) return -1;
        last_cr_send_ms = ppp_now_ms();
      }
      continue;
    }
    const uint8_t *p = in;
    size_t len = (size_t)n;
    ppp_strip(in, (size_t)n, &p, &len);
    uint16_t proto = 0u;
    size_t proto_len = 0u;
    if (ppp_read_protocol(p, len, &proto, &proto_len) != 0) {
      tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp ipcp: skip malformed frame len=%zu", len);
      continue;
    }
    if (proto != PROTO_IPCP) {
      if (proto == PROTO_LCP && len >= proto_len + 1u) {
        tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp ipcp: skip LCP code=%u while awaiting Configure-Ack",
                          (unsigned)p[proto_len]);
      } else {
        tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp ipcp: skip proto=0x%04x len=%zu", (unsigned)proto, len);
      }
      continue;
    }
    ppp_log_rx_summary(p, len, "ipcp");
    if (proto_len != 2u || len < 6u) {
      tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp ipcp: skip unexpected protocol-field-len=%zu", proto_len);
      continue;
    }
    uint8_t code = p[2];
    uint8_t rid = p[3];
    uint16_t ipcplen = util_read_be16(p + 4);
    if (ipcplen < 4u || 2u + (size_t)ipcplen > len) {
      tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                        "ppp ipcp: skip invalid-length code=%u id=%u ipcplen=%u len=%zu", (unsigned)code,
                        (unsigned)rid, (unsigned)ipcplen, len);
      continue;
    }
    const uint8_t *ipcp_msg = p + 2u;

    if (code == 1) {
      if (!ipcp_peer_framing_seen) {
        ipcp_peer_framing_seen = 1;
        ipcp_out_include_ac = (in[0] == 0xff && in[1] == 0x03) ? 1 : 0;
        tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp ipcp peer framing addr_ctl=%d", ipcp_out_include_ac);
      }
      uint8_t peer_gw[4];
      if (ipcp_opts_first_ipv4_option(ipcp_msg, ipcplen, IPCP_OPT_IP_ADDRESS, peer_gw) == 0) {
        memcpy(ppp->peer_ip, peer_gw, 4);
        tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp ipcp: peer Configure-Request IP=%u.%u.%u.%u",
                          (unsigned)peer_gw[0], (unsigned)peer_gw[1], (unsigned)peer_gw[2], (unsigned)peer_gw[3]);
      }
      uint8_t ackbuf[256];
      size_t ack_ppp_len = 2u + (size_t)ipcplen;
      size_t prefix = (size_t)(p - in);
      size_t total_ack = prefix + ack_ppp_len;
      if (total_ack > (size_t)n || total_ack > sizeof(ackbuf)) continue;
      memcpy(ackbuf, in, total_ack);
      if (util_read_be16(ackbuf + prefix) != PROTO_IPCP) continue;
      ackbuf[prefix + 2u] = 2;
      tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp ipcp: sending Configure-Ack for peer id=%u", (unsigned)rid);
      if (send_ppp(esp_fd, esp, peer, peer_len, l2tp, ackbuf, total_ack) < 0) return -1;
      if (ppp_now_ms() - last_cr_send_ms >= resend_after_ms) {
        tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                          "ppp ipcp: resend current Configure-Request id=%u reason=peer-configure-request",
                          (unsigned)id);
        if (send_ppp(esp_fd, esp, peer, peer_len, l2tp, pkt, (size_t)plen) < 0) return -1;
        last_cr_send_ms = ppp_now_ms();
      }
      continue;
    }

    if (code == 2 && rid == id) {
      if (ipcp_opts_first_ipv4_option(ipcp_msg, ipcplen, IPCP_OPT_IP_ADDRESS, ppp->local_ip) != 0) {
        memcpy(ppp->local_ip, req_ip, 4);
      }
      if (ipcp_ipv4_is_zero(ppp->local_ip)) {
        ppp->local_ip[0] = 10;
        ppp->local_ip[1] = 0;
        ppp->local_ip[2] = 0;
        ppp->local_ip[3] = 2;
      }
      ppp->have_ip = 1;
      if (include_primary_dns_opt &&
          ipcp_opts_first_ipv4_option(ipcp_msg, ipcplen, IPCP_OPT_PRIMARY_DNS, ppp->primary_dns) != 0) {
        memcpy(ppp->primary_dns, req_primary_dns, 4);
      }
      if (!ipcp_ipv4_is_zero(ppp->primary_dns)) {
        ppp->have_primary_dns = 1;
      }
      if (include_secondary_dns_opt &&
          ipcp_opts_first_ipv4_option(ipcp_msg, ipcplen, IPCP_OPT_SECONDARY_DNS, ppp->secondary_dns) != 0) {
        memcpy(ppp->secondary_dns, req_secondary_dns, 4);
      }
      if (!ipcp_ipv4_is_zero(ppp->secondary_dns)) {
        ppp->have_secondary_dns = 1;
      }
      tunnel_engine_log(
          ANDROID_LOG_DEBUG, LOG_TAG,
          "ppp ipcp: Configure-Ack id=%u local=%u.%u.%u.%u primary_dns=%u.%u.%u.%u secondary_dns=%u.%u.%u.%u",
          (unsigned)id, (unsigned)ppp->local_ip[0], (unsigned)ppp->local_ip[1], (unsigned)ppp->local_ip[2],
          (unsigned)ppp->local_ip[3], (unsigned)ppp->primary_dns[0], (unsigned)ppp->primary_dns[1],
          (unsigned)ppp->primary_dns[2], (unsigned)ppp->primary_dns[3], (unsigned)ppp->secondary_dns[0],
          (unsigned)ppp->secondary_dns[1], (unsigned)ppp->secondary_dns[2], (unsigned)ppp->secondary_dns[3]);
      got_ack = 1;
      break;
    }
    if (code == 2) {
      tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp ipcp: skip unexpected Configure-Ack id=%u want=%u",
                        (unsigned)rid, (unsigned)id);
      continue;
    }

    if (code == 3 && rid == id) {
      if (ipcp_opts_first_ipv4_option(ipcp_msg, ipcplen, IPCP_OPT_IP_ADDRESS, req_ip) == 0) {
        tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp ipcp: Configure-Nak id=%u suggested=%u.%u.%u.%u",
                          (unsigned)rid, (unsigned)req_ip[0], (unsigned)req_ip[1], (unsigned)req_ip[2],
                          (unsigned)req_ip[3]);
      }
      if (ipcp_opts_first_ipv4_option(ipcp_msg, ipcplen, IPCP_OPT_PRIMARY_DNS, req_primary_dns) == 0) {
        tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp ipcp: Configure-Nak id=%u primary_dns=%u.%u.%u.%u",
                          (unsigned)rid, (unsigned)req_primary_dns[0], (unsigned)req_primary_dns[1],
                          (unsigned)req_primary_dns[2], (unsigned)req_primary_dns[3]);
      }
      if (ipcp_opts_first_ipv4_option(ipcp_msg, ipcplen, IPCP_OPT_SECONDARY_DNS, req_secondary_dns) == 0) {
        tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp ipcp: Configure-Nak id=%u secondary_dns=%u.%u.%u.%u",
                          (unsigned)rid, (unsigned)req_secondary_dns[0], (unsigned)req_secondary_dns[1],
                          (unsigned)req_secondary_dns[2], (unsigned)req_secondary_dns[3]);
      }
      id++;
      plen = ipcp_build_cr(pkt, sizeof(pkt), id, req_ip, ipcp_out_include_ac, include_ip_opt, req_primary_dns,
                           include_primary_dns_opt, req_secondary_dns, include_secondary_dns_opt);
      if (plen < 0) return -1;
      if (send_ppp(esp_fd, esp, peer, peer_len, l2tp, pkt, (size_t)plen) < 0) return -1;
      last_cr_send_ms = ppp_now_ms();
      continue;
    }
    if (code == 3) {
      tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp ipcp: skip unexpected Configure-Nak id=%u want=%u",
                        (unsigned)rid, (unsigned)id);
      continue;
    }

    if (code == 4 && rid == id) {
      tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "ppp ipcp: Configure-Reject id=%u", (unsigned)rid);
      ipcp_apply_conf_reject_opts(ipcp_msg, ipcplen, &include_ip_opt, &include_primary_dns_opt,
                                  &include_secondary_dns_opt);
      id++;
      plen = ipcp_build_cr(pkt, sizeof(pkt), id, req_ip, ipcp_out_include_ac, include_ip_opt, req_primary_dns,
                           include_primary_dns_opt, req_secondary_dns, include_secondary_dns_opt);
      if (plen < 0) return -1;
      if (send_ppp(esp_fd, esp, peer, peer_len, l2tp, pkt, (size_t)plen) < 0) return -1;
      last_cr_send_ms = ppp_now_ms();
      continue;
    }
    if (code == 4) {
      tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp ipcp: skip unexpected Configure-Reject id=%u want=%u",
                        (unsigned)rid, (unsigned)id);
      continue;
    }
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp ipcp: skip unsupported code=%u id=%u", (unsigned)code,
                      (unsigned)rid);
  }

  if (!got_ack) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "ppp ipcp: timeout without Configure-Ack");
    return -1;
  }
  return 0;
}

#define PPP_IPPROTO_TCP 6u
#define PPP_IPPROTO_UDP 17u

/** RFC 1071 Internet checksum fold (16-bit one's complement). */
static uint16_t ppp_inet_checksum_fold(uint32_t sum) {
  while (sum >> 16) {
    sum = (sum & 0xffffu) + (sum >> 16);
  }
  return (uint16_t)~sum;
}

static uint16_t ppp_sanitize_link_mtu(int tun_mtu) {
  if (tun_mtu < 576) return 576u;
  if (tun_mtu > 1500) return 1500u;
  return (uint16_t)tun_mtu;
}

static uint16_t ppp_tcp_mss_for_link_mtu(uint16_t link_mtu) {
  return link_mtu > 40u ? (uint16_t)(link_mtu - 40u) : 536u;
}

static uint32_t ppp_csum_acc_bytes(uint32_t sum, const uint8_t *p, size_t n) {
  for (size_t i = 0; i < n; i += 2) {
    uint32_t w = (uint32_t)p[i] << 8;
    if (i + 1 < n) w |= p[i + 1];
    sum += w;
  }
  return sum;
}

static int ppp_clamp_tcp_syn_mss(uint8_t *pkt, size_t len, uint16_t clamp_mss, uint16_t *old_mss_out,
                                 uint16_t *new_mss_out) {
  if (pkt == NULL || len < 20u || clamp_mss == 0u) return 0;
  if ((pkt[0] >> 4) != 4u) return 0;
  unsigned ihl = (unsigned)(pkt[0] & 0x0fu) * 4u;
  if (ihl < 20u || ihl > len) return 0;
  uint16_t tot_len_be = util_read_be16(pkt + 2);
  if (tot_len_be < (uint16_t)ihl) return 0;
  size_t ip_len = (size_t)tot_len_be;
  if (ip_len > len) return 0;
  uint16_t frag = util_read_be16(pkt + 6);
  if ((frag & 0x3fffu) != 0u) return 0;
  if (pkt[9] != PPP_IPPROTO_TCP) return 0;

  size_t l4_len = ip_len - ihl;
  if (l4_len < 20u || ihl + l4_len > len) return 0;
  uint8_t *l4 = pkt + ihl;
  unsigned tcp_hlen = (unsigned)(l4[12] >> 4) * 4u;
  if (tcp_hlen < 20u || tcp_hlen > l4_len) return 0;
  if ((l4[13] & 0x02u) == 0u) return 0;

  size_t opt = 20u;
  while (opt < tcp_hlen) {
    uint8_t kind = l4[opt];
    if (kind == 0u) break;
    if (kind == 1u) {
      opt++;
      continue;
    }
    if (opt + 1u >= tcp_hlen) break;
    uint8_t olen = l4[opt + 1u];
    if (olen < 2u || opt + (size_t)olen > tcp_hlen) break;
    if (kind == 2u && olen == 4u) {
      uint16_t old_mss = util_read_be16(l4 + opt + 2u);
      if (old_mss > clamp_mss) {
        util_write_be16(l4 + opt + 2u, clamp_mss);
        util_write_be16(l4 + 16u, 0u);
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

/**
 * Android VPN TUN often delivers IPv4 with header / TCP / UDP checksum 0 (offload semantics).
 * LNS stacks may drop those; normalize before PPP encapsulation.
 */
static void ppp_fix_tun_ipv4_checksums(uint8_t *pkt, size_t len) {
  if (len < 20u) return;
  if ((pkt[0] >> 4) != 4u) return;
  unsigned ihl = (unsigned)(pkt[0] & 0x0fu) * 4u;
  if (ihl < 20u || ihl > len) return;
  uint16_t tot_len_be = util_read_be16(pkt + 2);
  if (tot_len_be < (uint16_t)ihl) return;
  size_t ip_len = (size_t)tot_len_be;
  if (ip_len > len) return;

  if (util_read_be16(pkt + 10) == 0u) {
    util_write_be16(pkt + 10, 0);
    uint32_t sum = ppp_csum_acc_bytes(0, pkt, ihl);
    util_write_be16(pkt + 10, ppp_inet_checksum_fold(sum));
  }

  uint16_t frag = util_read_be16(pkt + 6);
  unsigned frag_off = (unsigned)(frag & 0x1fffu);
  if (frag_off != 0u) return;

  uint8_t proto = pkt[9];
  size_t l4_len = ip_len - ihl;
  if (l4_len < 8u || ihl + l4_len > len) return;
  uint8_t *l4 = pkt + ihl;

  if (proto == PPP_IPPROTO_UDP) {
    if (util_read_be16(l4 + 6) != 0u) return;
    uint32_t sum = 0;
    sum = ppp_csum_acc_bytes(sum, pkt + 12, 4);
    sum = ppp_csum_acc_bytes(sum, pkt + 16, 4);
    sum += (uint32_t)PPP_IPPROTO_UDP;
    sum += (uint32_t)l4_len;
    sum = ppp_csum_acc_bytes(sum, l4, l4_len);
    uint16_t c = ppp_inet_checksum_fold(sum);
    if (c == 0u) c = 0xffffu;
    util_write_be16(l4 + 6, c);
  } else if (proto == PPP_IPPROTO_TCP) {
    if (l4_len < 20u) return;
    if (util_read_be16(l4 + 16) != 0u) return;
    uint32_t sum = 0;
    sum = ppp_csum_acc_bytes(sum, pkt + 12, 4);
    sum = ppp_csum_acc_bytes(sum, pkt + 16, 4);
    sum += (uint32_t)PPP_IPPROTO_TCP;
    sum += (uint32_t)l4_len;
    sum = ppp_csum_acc_bytes(sum, l4, l4_len);
    util_write_be16(l4 + 16, ppp_inet_checksum_fold(sum));
  }
}

/* --- Exported PPP API (negotiate, TUN egress, inbound dispatch) --- */

static size_t ppp_write_frame_prefix(uint8_t *buf, size_t cap, uint16_t proto, const ppp_session_t *ppp);

int ppp_negotiate(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len, l2tp_session_t *l2tp,
                  const char *user, const char *password, int tun_mtu, ppp_session_t *ppp) {
  memset(ppp, 0, sizeof(*ppp));
  ppp->link_mtu = ppp_sanitize_link_mtu(tun_mtu);
  ppp->tcp_mss = ppp_tcp_mss_for_link_mtu(ppp->link_mtu);
  tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ppp negotiate: start link_mtu=%u advertised_mru=%u tcp_mss=%u",
                    (unsigned)ppp->link_mtu, (unsigned)ppp->link_mtu, (unsigned)ppp->tcp_mss);
  ppp_auth_kind_t auth = PPP_AUTH_PAP;
  if (ppp_lcp_negotiate(esp_fd, esp, peer, peer_len, l2tp, &auth, ppp) != 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "ppp negotiate: failed during LCP");
    return -1;
  }

  if (auth == PPP_AUTH_MSCHAPV2) {
    if (ppp_auth_mschapv2(esp_fd, esp, peer, peer_len, l2tp, user, password) != 0) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "ppp negotiate: auth failure method=mschapv2");
      return -1;
    }
  } else if (auth == PPP_AUTH_CHAP_MD5) {
    if (ppp_auth_chap_md5(esp_fd, esp, peer, peer_len, l2tp, user, password) != 0) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "ppp negotiate: auth failure method=chap-md5");
      return -1;
    }
  } else {
    if (ppp_auth_pap(esp_fd, esp, peer, peer_len, l2tp, user, password) != 0) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "ppp negotiate: auth failure method=pap");
      return -1;
    }
  }

  if (ppp_ipcp_negotiate(esp_fd, esp, peer, peer_len, l2tp, ppp) != 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "ppp negotiate: IPCP did not ack");
    return -1;
  }

  return 0;
}

int ppp_encapsulate_and_send(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len,
                             l2tp_session_t *l2tp, ppp_session_t *ppp, const uint8_t *ip_packet, size_t len) {
  uint8_t buf[4096];
  size_t prefix = ppp_write_frame_prefix(buf, sizeof(buf), PROTO_IP, ppp);
  if (prefix == 0u || len + prefix > sizeof(buf)) return -1;
  engine_dp_note_tun_ipv4_outbound(ip_packet, len);
  uint8_t *inner_ip = buf + prefix;
  memcpy(inner_ip, ip_packet, len);
  if (ppp != NULL && ppp->tcp_mss >= 536u) {
    uint16_t old_mss = 0u;
    uint16_t new_mss = 0u;
    if (ppp_clamp_tcp_syn_mss(inner_ip, len, ppp->tcp_mss, &old_mss, &new_mss) > 0 &&
        !ppp->tcp_mss_clamp_logged) {
      ppp->tcp_mss_clamp_logged = 1;
      tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                        "ppp tcp syn mss clamp old=%u new=%u link_mtu=%u", (unsigned)old_mss,
                        (unsigned)new_mss, (unsigned)ppp->link_mtu);
    }
  }
  ppp_fix_tun_ipv4_checksums(inner_ip, len);
  return l2tp_send_ppp(esp_fd, esp, peer, peer_len, l2tp, buf, len + prefix);
}

static size_t ppp_write_frame_prefix(uint8_t *buf, size_t cap, uint16_t proto, const ppp_session_t *ppp) {
  if (buf == NULL) return 0u;
  int compress_proto = ppp != NULL && ppp->lcp_pfc && proto <= 0x00ffu && (proto & 0x0001u) != 0u;
  if (ppp != NULL && ppp->lcp_acfc) {
    if (compress_proto) {
      if (cap < 1u) return 0u;
      buf[0] = (uint8_t)proto;
      return 1u;
    }
    if (cap < 2u) return 0u;
    util_write_be16(buf, proto);
    return 2u;
  }
  if (compress_proto) {
    if (cap < 3u) return 0u;
    buf[0] = 0xff;
    buf[1] = 0x03;
    buf[2] = (uint8_t)proto;
    return 3u;
  }
  if (cap < 4u) return 0u;
  buf[0] = 0xff;
  buf[1] = 0x03;
  util_write_be16(buf + 2u, proto);
  return 4u;
}

/** RFC 1661 LCP Code 8 Protocol-Reject: peer used a network protocol we do not implement. */
static int ppp_send_lcp_protocol_reject(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len,
                                        l2tp_session_t *l2tp, ppp_session_t *ppp, uint16_t rejected_proto,
                                        const uint8_t *rejected_info, size_t rejected_info_len) {
  if (esp == NULL || peer == NULL || l2tp == NULL) return -1;
  uint8_t buf[256];
  const size_t lcp_fixed = 6; /* code + id + length + rejected_protocol */
  const size_t ppp_prefix = ppp_write_frame_prefix(buf, sizeof(buf), PROTO_LCP, ppp);
  if (ppp_prefix == 0u) return -1;
  size_t ilen = rejected_info_len;
  if (ilen > sizeof(buf) - ppp_prefix - lcp_fixed) ilen = sizeof(buf) - ppp_prefix - lcp_fixed;

  static uint8_t s_proto_reject_id = 200;
  uint8_t id = ++s_proto_reject_id;
  if (id == 0) id = 1;

  const size_t lcp_len = lcp_fixed + ilen;
  if (ppp_prefix + lcp_len > sizeof(buf)) return -1;

  buf[ppp_prefix + 0u] = (uint8_t)LCP_CODE_PROTOCOL_REJECT;
  buf[ppp_prefix + 1u] = id;
  util_write_be16(buf + ppp_prefix + 2u, (uint16_t)lcp_len);
  util_write_be16(buf + ppp_prefix + 4u, rejected_proto);
  if (ilen != 0 && rejected_info != NULL) memcpy(buf + ppp_prefix + 6u, rejected_info, ilen);
  return l2tp_send_ppp(esp_fd, esp, peer, peer_len, l2tp, buf, ppp_prefix + lcp_len);
}

/** RFC 1661 LCP Echo-Reply: answer peer keepalive on the data channel (common with xl2tpd/pppd). */
static int ppp_send_lcp_echo_reply(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len,
                                   l2tp_session_t *l2tp, ppp_session_t *ppp, const uint8_t *req_lcp, size_t req_len) {
  if (esp == NULL || peer == NULL || l2tp == NULL || req_len < 4) return -1;
  if (req_lcp[0] != (uint8_t)LCP_CODE_ECHO_REQUEST) return -1;
  uint8_t id = req_lcp[1];
  uint16_t lcp_len = util_read_be16(req_lcp + 2);
  if (lcp_len < 8 || (size_t)lcp_len > req_len) return -1;
  uint8_t buf[256];
  const size_t ppp_prefix = ppp_write_frame_prefix(buf, sizeof(buf), PROTO_LCP, ppp);
  if (ppp_prefix == 0u) return -1;
  if (ppp_prefix + (size_t)lcp_len > sizeof(buf)) return -1;

  buf[ppp_prefix + 0u] = (uint8_t)LCP_CODE_ECHO_REPLY;
  buf[ppp_prefix + 1u] = id;
  util_write_be16(buf + ppp_prefix + 2u, lcp_len);
  /* We never negotiated Magic-Number, so the reply value stays zero per RFC 1661 section 5.8. */
  util_write_be32(buf + ppp_prefix + 4u, 0u);
  if (lcp_len > 8u) memcpy(buf + ppp_prefix + 8u, req_lcp + 8u, (size_t)lcp_len - 8u);
  return l2tp_send_ppp(esp_fd, esp, peer, peer_len, l2tp, buf, ppp_prefix + (size_t)lcp_len);
}

int ppp_dispatch_ppp_frame(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len,
                           l2tp_session_t *l2tp, ppp_session_t *ppp, const uint8_t *frame, size_t len,
                           packet_endpoint_t *endpoint) {
  if (len < 2) return -1;
  const uint8_t log_b0 = frame[0];
  const uint8_t log_b1 = frame[1];
  const size_t log_frame_len = len;
  const uint8_t *p = frame;
  if (p[0] == 0xff && p[1] == 0x03) {
    p += 2;
    len -= 2;
  }
  uint16_t proto = 0u;
  size_t proto_len = 0u;
  if (ppp_read_protocol(p, len, &proto, &proto_len) != 0 || len < proto_len) return -1;
  p += proto_len;
  len -= proto_len;
  if (proto == PROTO_IP) {
    if (packet_endpoint_write(endpoint, p, len) != 0) {
      static time_t s_last_tun_write_warn;
      time_t now = time(NULL);
      if (now - s_last_tun_write_warn >= 3) {
        s_last_tun_write_warn = now;
        tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "ppp ip write endpoint=%s failed errno=%d len=%zu",
                          endpoint != NULL && endpoint->name != NULL ? endpoint->name : "unknown", errno, len);
      }
      return -1;
    }
    engine_dp_note_tun_ipv4_written(len);
    if (len >= 10u) {
      engine_dp_note_tun_ipv4_protocol(p[9]);
    }
    return 0;
  }
  if (proto == PROTO_IPV6CP) {
    static int s_ipv6cp_info_once;
    if (esp != NULL && peer != NULL && l2tp != NULL) {
      if (ppp_send_lcp_protocol_reject(esp_fd, esp, peer, peer_len, l2tp, ppp, PROTO_IPV6CP, p, len) >= 0) {
        if (!s_ipv6cp_info_once) {
          s_ipv6cp_info_once = 1;
          tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "IPv6CP: sent LCP Protocol-Reject (IPv4-only link)");
        }
      }
    }
    return 0;
  }
  if (proto == PROTO_LCP) {
    if (len >= 4 && p[0] == (uint8_t)LCP_CODE_ECHO_REQUEST && esp != NULL && peer != NULL && l2tp != NULL) {
      if (ppp_send_lcp_echo_reply(esp_fd, esp, peer, peer_len, l2tp, ppp, p, len) >= 0) return 0;
    }
    static time_t s_last_lcp_dataplane_warn;
    time_t now_lcp = time(NULL);
    if (now_lcp - s_last_lcp_dataplane_warn >= 5) {
      s_last_lcp_dataplane_warn = now_lcp;
      tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG,
                        "ppp dispatch LCP on data channel code=%u inner_len=%zu (echo handled if code=9)",
                        len >= 1 ? (unsigned)p[0] : 0u, len);
    }
    return 0;
  }
  static time_t s_last_nonip_warn;
  time_t now = time(NULL);
  if (now - s_last_nonip_warn >= 3) {
    s_last_nonip_warn = now;
    tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG,
                      "ppp dispatch non-ip proto=0x%04x frame_prefix=%02x%02x ppp_len=%zu (IPv4 0x0021 only)",
                      (unsigned)proto, (unsigned)log_b0, (unsigned)log_b1, log_frame_len);
  }
  return 0;
}
