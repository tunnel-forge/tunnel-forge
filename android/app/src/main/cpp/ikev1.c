/*
 * IKEv1 Main Mode (PSK) and Quick Mode for ESP-over-UDP (RFC 2408/2409, NAT-T RFC 3947/3948).
 * Uses mbedtls for DH (MODP2048), PRF, and phase-1 ciphers. Empty PSK selects cleartext L2TP only
 * (cleartext_l2tp); otherwise opens the shared UDP socket used for IKE and ESP.
 */
#include "ikev1.h"
#include "ikev1_internal.h"

#include "engine.h"

#include <android/log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <mbedtls/cipher.h>
#include <mbedtls/ctr_drbg.h>
#include <mbedtls/dhm.h>
#include <mbedtls/entropy.h>
#include <mbedtls/md.h>
#include <mbedtls/md5.h>
#include <mbedtls/sha1.h>
#include <mbedtls/sha256.h>
#include <netdb.h>
#include <netinet/in.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define LOG_TAG "tunnel_engine"
static unsigned s_ike_keymap_log_once;
static unsigned s_ike_keymat_variants_log_once;

#ifndef TUNNEL_FORGE_KEYMAT_VARIANT
#define TUNNEL_FORGE_KEYMAT_VARIANT 1
#endif

// IKE DH group 14 (MODP2048): must match Phase 1 proposal and mbedtls_mpi_read_binary below.
#define IKE_DH_PUBKEY_BYTES 256

/* --- Diagnostics, sockets, ISAKMP crypto primitives (shared by Main and Quick Mode) --- */

static void ike_hex_dump(const char *prefix, const uint8_t *data, size_t len, size_t max_show) {
  size_t n = len < max_show ? len : max_show;
  char line[220];
  for (size_t i = 0; i < n; i += 16) {
    char *p = line;
    size_t rem = sizeof(line);
    int w = snprintf(p, rem, "%s +%04zu: ", prefix, i);
    if (w < 0 || (size_t)w >= rem) break;
    p += (size_t)w;
    rem -= (size_t)w;
    for (size_t j = 0; j < 16 && i + j < n; j++) {
      w = snprintf(p, rem, "%02x ", data[i + j]);
      if (w < 0 || (size_t)w >= rem) break;
      p += (size_t)w;
      rem -= (size_t)w;
    }
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "%s", line);
  }
  if (len > max_show) {
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "%s ... (%zu bytes total, truncated)", prefix, len);
  }
}

static void ike_log_isakmp_summary(const char *ctx, const uint8_t *in, int inlen) {
  if (in == NULL || inlen < 28) {
    tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "%s: short or null packet (inlen=%d)", ctx, inlen);
    return;
  }
  uint32_t isakmp_len = util_read_be32(in + 24);
  uint32_t mid = util_read_be32(in + 20);
  tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
 "%s: inlen=%d isakmp_len=%u first_np=%u exch=%u flags=0x%02x msgid=%u icookie=%02x%02x... rcookie=%02x%02x...",
                      ctx, inlen, isakmp_len, in[16], in[18], in[19], mid, in[0], in[1], in[8], in[9]);

  /* Cleartext payload walk is meaningless when the body is ciphertext (false "invalid payload len"). */
  if (in[19] & IKE_FLAG_ENC) {
    ike_hex_dump(ctx, in, (size_t)inlen, 128);
    return;
  }

  const uint8_t *p = in + 28;
  int left = inlen - 28;
  uint8_t np = in[16];
  for (int depth = 0; left >= 4 && np != IKE_PT_NONE && depth < 24; depth++) {
    uint16_t plen = util_read_be16(p + 2);
    if (plen < 4 || plen > (size_t)left) {
      tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "%s: invalid payload len=%u left=%d at depth=%d", ctx,
 (unsigned)plen, left, depth);
      break;
    }
    const uint8_t *body = p + 4;
    size_t blen = plen - 4;
    if (np == IKE_PT_NOTIFY && blen >= 8) {
      uint32_t doi = util_read_be32(body);
      uint8_t proto = body[4];
      uint8_t spi_sz = body[5];
      uint16_t ntype = util_read_be16(body + 6);
      tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG,
                          "%s: NOTIFY doi=%u proto=%u spi_len=%u type=%u (0x%04x) - server may reject proposal/PSK",
                          ctx, doi, proto, spi_sz, ntype, ntype);
    } else {
      tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "%s: payload[%d] type=%u total_len=%u body_len=%zu", ctx, depth,
                          np, (unsigned)plen, blen);
    }
    np = p[0];
    p += plen;
    left -= (int)plen;
  }
  ike_hex_dump(ctx, in, (size_t)inlen, 128);
}

static void ike_log_endpoint(const char *tag, const struct sockaddr *sa, socklen_t salen) {
  char host[INET_ADDRSTRLEN];
  if (sa->sa_family != AF_INET) {
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "%s: family=%d (not IPv4)", tag, sa->sa_family);
    return;
  }
  const struct sockaddr_in *sin = (const struct sockaddr_in *)sa;
  if (inet_ntop(AF_INET, &sin->sin_addr, host, sizeof(host)) == NULL) {
    tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "%s: inet_ntop failed", tag);
    return;
  }
  tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "%s: %s:%u", tag, host, (unsigned)ntohs(sin->sin_port));
}

extern const uint8_t rfc3526_modp2048_p[256];

static const uint8_t k_vid_rfc3947[IKE_VID_RFC3947_LEN] = {0x4a, 0x13, 0x1c, 0x81, 0x07, 0x03, 0x58, 0x45,
                                                           0x5c, 0x57, 0x28, 0xf2, 0x0e, 0x95, 0x45, 0x2f};

static int resolve_udp(const char *host, uint16_t port, struct sockaddr_storage *out, socklen_t *olen) {
  struct addrinfo hints;
  memset(&hints, 0, sizeof(hints));
  hints.ai_family = AF_UNSPEC;
  hints.ai_socktype = SOCK_DGRAM;
  hints.ai_protocol = IPPROTO_UDP;
  struct addrinfo *res = NULL;
  char portbuf[8];
  snprintf(portbuf, sizeof(portbuf), "%u", (unsigned)port);
  int r = getaddrinfo(host, portbuf, &hints, &res);
  if (r != 0 || res == NULL) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "resolve_udp host=%s port=%s: %s", host, portbuf,
                        r ? gai_strerror(r) : "getaddrinfo returned null");
    return -1;
  }
  memcpy(out, res->ai_addr, res->ai_addrlen);
  *olen = (socklen_t)res->ai_addrlen;
  freeaddrinfo(res);
  return 0;
}

static int cleartext_l2tp(const char *server, ike_session_t *ike, esp_keys_t *esp) {
  memset(ike, 0, sizeof(*ike));
  memset(esp, 0, sizeof(*esp));
  if (resolve_udp(server, L2TP_PORT, &ike->peer, &ike->peer_len) != 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "cleartext_l2tp: resolve %s failed", server);
    return -1;
  }
  ike_log_endpoint("cleartext_l2tp peer", (struct sockaddr *)&ike->peer, ike->peer_len);
  int fd = socket(ike->peer.ss_family, SOCK_DGRAM, IPPROTO_UDP);
  if (fd < 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "cleartext_l2tp: socket errno=%d", errno);
    return -1;
  }
  if (util_protect_fd(fd) != 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "cleartext_l2tp: protect(fd=%d) failed", fd);
    close(fd);
    return -1;
  }
  if (connect(fd, (struct sockaddr *)&ike->peer, ike->peer_len) != 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "cleartext_l2tp: connect errno=%d", errno);
    close(fd);
    return -1;
  }
  ike->esp_fd = fd;
  esp->udp_encap = 0;
  tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG,
                      "L2TP without IPsec: plaintext UDP to server (IPsec skipped)");
  return 0;
}

static int prf_hmac_sha1(const uint8_t *key, size_t keylen, const uint8_t *data, size_t datalen, uint8_t out[20]) {
  const mbedtls_md_info_t *md = mbedtls_md_info_from_type(MBEDTLS_MD_SHA1);
  mbedtls_md_context_t ctx;
  mbedtls_md_init(&ctx);
  if (mbedtls_md_setup(&ctx, md, 1) != 0) {
    mbedtls_md_free(&ctx);
    return -1;
  }
  mbedtls_md_hmac_starts(&ctx, key, keylen);
  mbedtls_md_hmac_update(&ctx, data, datalen);
  mbedtls_md_hmac_finish(&ctx, out);
  mbedtls_md_free(&ctx);
  return 0;
}

static void strip_leading_zeros(uint8_t *buf, size_t *len) {
  while (*len > 1 && buf[0] == 0) {
    memmove(buf, buf + 1, *len - 1);
    (*len)--;
  }
}

static int ike_send_recv(int fd, const struct sockaddr *peer, socklen_t peer_len, const uint8_t *out, size_t out_len,
                         uint8_t *in, size_t in_cap, int timeout_ms, int prefix4500) {
  uint8_t wr[4096];
  const uint8_t *sendptr = out;
  size_t sendlen = out_len;
  if (prefix4500) {
    if (out_len + 4 > sizeof(wr)) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "ike_send_recv: packet too large for NAT-T wrapper");
      return -1;
    }
    memset(wr, 0, 4);
    memcpy(wr + 4, out, out_len);
    sendptr = wr;
    sendlen = out_len + 4;
  }

  static const int retry_ms[] = {2000, 4000, 5000, 5000};
  int attempts =
      (timeout_ms >= 16000) ? 4 : (timeout_ms >= 10000) ? 3 : (timeout_ms >= 4000) ? 2 : 1;
  int remaining = timeout_ms;

  for (int attempt = 0; attempt < attempts && remaining > 0; attempt++) {
    if (tunnel_should_stop()) {
      tunnel_engine_log(ANDROID_LOG_INFO, LOG_TAG, "ike_send_recv: canceled before send");
      return -1;
    }
    ssize_t ns = sendto(fd, sendptr, sendlen, 0, peer, peer_len);
    if (ns != (ssize_t)sendlen) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG,
                          "ike_send_recv: sendto ret=%zd want=%zu errno=%d nat_t=%d", ns, sendlen, errno, prefix4500);
      return -1;
    }
    int wait = (attempt < 4) ? retry_ms[attempt] : 5000;
    if (wait > remaining) wait = remaining;
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                        "ike_send_recv: attempt %d/%d wait=%d ms remaining=%d ms", attempt + 1, attempts, wait,
                        remaining);
    int waited = 0;
    int pr = 0;
    struct pollfd pfd = {.fd = fd, .events = POLLIN};
    while (waited < wait) {
      if (tunnel_should_stop()) {
        tunnel_engine_log(ANDROID_LOG_INFO, LOG_TAG, "ike_send_recv: canceled while waiting for reply");
        return -1;
      }
      int slice = wait - waited;
      if (slice > 200) slice = 200;
      pr = poll(&pfd, 1, slice);
      waited += slice;
      if (pr != 0) break;
    }
    remaining -= waited;
    if (pr < 0) {
      if (tunnel_should_stop()) {
        tunnel_engine_log(ANDROID_LOG_INFO, LOG_TAG, "ike_send_recv: canceled after poll interruption");
        return -1;
      }
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "ike_send_recv: poll errno=%d", errno);
      return -1;
    }
    if (pr == 0) {
      if (tunnel_should_stop()) {
        tunnel_engine_log(ANDROID_LOG_INFO, LOG_TAG, "ike_send_recv: canceled after wait budget expired");
        return -1;
      }
      tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "ike_send_recv: no reply after %d ms (attempt %d)", waited,
                          attempt + 1);
      continue;
    }
    ssize_t n = recv(fd, in, in_cap, 0);
    if (n <= 0) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "ike_send_recv: recv ret=%zd errno=%d", n, errno);
      return -1;
    }
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "ike_send_recv: got UDP reply %zd bytes nat_t=%d (attempt %d)", n,
                        prefix4500, attempt + 1);
    if (prefix4500 && n >= 4 && util_read_be32(in) == 0u) {
      memmove(in, in + 4, (size_t)n - 4);
      n -= 4;
    }
    return (int)n;
  }
  tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG,
                      "ike_send_recv: all %d attempts failed (total timeout %d ms)", attempts, timeout_ms);
  return -1;
}

static int isakmp_3des_decrypt(const uint8_t key24[24], const uint8_t iv_in[8], const uint8_t *ct, size_t ct_len,
                               uint8_t *plain, size_t *plain_len, uint8_t iv_out[8]);
static int isakmp_aes128_decrypt(const uint8_t key16[16], const uint8_t iv_in[16], const uint8_t *ct, size_t ct_len,
                                 uint8_t *plain, size_t *plain_len, uint8_t iv_out[16]);

/** Send Quick Mode msg3 on the NAT-T socket (optional 4-byte RFC 3948 prefix). */
static int ike_send_qm3_payload(int fd, const struct sockaddr *peer, socklen_t peer_len, const uint8_t *pkt,
                                size_t pkt_len, int prefix4500) {
  uint8_t wr[4096];
  const uint8_t *sendptr = pkt;
  size_t sendlen = pkt_len;
  if (prefix4500) {
    if (pkt_len + 4 > sizeof(wr)) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "ike_send_qm3_payload: packet too large for NAT-T wrapper");
      return -1;
    }
    memset(wr, 0, 4);
    memcpy(wr + 4, pkt, pkt_len);
    sendptr = wr;
    sendlen = pkt_len + 4;
  }
  ssize_t ns = sendto(fd, sendptr, sendlen, 0, peer, peer_len);
  if (ns != (ssize_t)sendlen) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "ike_send_qm3_payload: sendto ret=%zd want=%zu errno=%d", ns,
                      sendlen, errno);
    return -1;
  }
  return 0;
}

/**
 * True if datagram is Libreswan's Quick Mode msg2 retransmit: decrypt with IV chain from QM1 (qm1_tail)
 * and inner payloads include SA. Other Quick encrypted packets with the same msgid are logged by the caller.
 */
static int ike_is_duplicate_qm2_retransmit(int p1_aes, const uint8_t *aeskey, const uint8_t *deskey,
                                           const uint8_t *qm1_tail, const uint8_t *in, int inlen,
                                           uint32_t qm_mid) {
  if (inlen < 28 || in[17] != 0x10) return 0;
  if (in[18] != IKE_EXCH_QUICK || (in[19] & IKE_FLAG_ENC) == 0) return 0;
  if (util_read_be32(in + 20) != qm_mid) return 0;
  if (in[16] != IKE_PT_HASH) return 0;
  size_t enc_len = (size_t)inlen - 28;
  if (enc_len < 16) return 0;
  uint8_t dec[768];
  size_t dec_len = 0;
  uint8_t iv_out[16];
  if (p1_aes) {
    if (isakmp_aes128_decrypt(aeskey, qm1_tail, in + 28, enc_len, dec, &dec_len, iv_out) != 0) return 0;
  } else {
    if (isakmp_3des_decrypt(deskey, qm1_tail, in + 28, enc_len, dec, &dec_len, iv_out) != 0) return 0;
  }
  size_t walk = 0;
  uint8_t cur = in[16];
  while (walk + 4 <= dec_len && cur != IKE_PT_NONE) {
    uint16_t pl = util_read_be16(dec + walk + 2);
    if (pl < 4 || walk + pl > dec_len) return 0;
    if (cur == IKE_PT_SA) return 1;
    cur = dec[walk];
    walk += pl;
  }
  return 0;
}

/**
 * Wait for QM3 completion: ignore duplicate server QM2 (common Libreswan retransmit), resend QM3.
 * Accept optional Informational ack. If peer is silent after timeout, continue (many stacks send nothing).
 */
static int ike_qm3_finish_recv_loop(int fd, const struct sockaddr *peer, socklen_t peer_len, const uint8_t *qm3_pkt,
                                    size_t qm3_len, uint8_t *in, size_t in_cap, int prefix4500, uint32_t qm_mid,
                                    int p1_aes, const uint8_t *aeskey, const uint8_t *deskey,
                                    const uint8_t *qm1_tail) {
  const int total_ms = 16000;
  const int slice_ms = 400;
  int elapsed = 0;
  int resend_count = 0;

  if (ike_send_qm3_payload(fd, peer, peer_len, qm3_pkt, qm3_len, prefix4500) != 0) return -1;

  while (elapsed < total_ms) {
    if (tunnel_should_stop()) {
      tunnel_engine_log(ANDROID_LOG_INFO, LOG_TAG, "Quick Mode msg3: canceled while waiting for completion");
      return -1;
    }
    struct pollfd pfd = {.fd = fd, .events = POLLIN};
    int pr = poll(&pfd, 1, slice_ms);
    if (pr < 0) {
      if (errno == EINTR) continue;
      if (tunnel_should_stop()) {
        tunnel_engine_log(ANDROID_LOG_INFO, LOG_TAG, "Quick Mode msg3: canceled after poll interruption");
        return -1;
      }
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "ike_qm3_finish_recv_loop: poll errno=%d", errno);
      return -1;
    }
    elapsed += slice_ms;
    if (pr == 0) continue;

    ssize_t n = recv(fd, in, in_cap, 0);
    if (n < 0) {
      if (errno == EINTR) continue;
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "ike_qm3_finish_recv_loop: recv errno=%d", errno);
      return -1;
    }
    if (n == 0) continue;

    const uint8_t *p = in;
    ssize_t nl = n;
    if (prefix4500 && nl >= 4 && util_read_be32(p) == 0u) {
      p += 4;
      nl -= 4;
    }
    if (nl < 28) continue;
    if (p[17] != 0x10) continue;

    if (p[18] == IKE_EXCH_INFO) {
      ike_log_isakmp_summary("Quick Mode msg3 peer reply (Informational)", p, (int)nl);
      return 0;
    }

    if (p[18] == IKE_EXCH_QUICK && (p[19] & IKE_FLAG_ENC) && util_read_be32(p + 20) == qm_mid) {
      if (ike_is_duplicate_qm2_retransmit(p1_aes, aeskey, deskey, qm1_tail, p, (int)nl, qm_mid)) {
        tunnel_engine_log(
            ANDROID_LOG_DEBUG, LOG_TAG,
            "Quick Mode: server msg2 retransmit while waiting for QM3 ack; resending HASH(3) (resend=%d)",
            resend_count);
        if (resend_count < 16 && ike_send_qm3_payload(fd, peer, peer_len, qm3_pkt, qm3_len, prefix4500) == 0)
          resend_count++;
        continue;
      }
    }

    ike_log_isakmp_summary("Quick Mode msg3 unexpected packet while waiting", p, (int)nl);
  }

  tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                      "Quick Mode msg3: finished wait %d ms without Informational (peer often silent; ok)", total_ms);
  return 0;
}

static void natd_hash(const uint8_t cky_i[8], const uint8_t cky_r[8],
                      const uint8_t *ip4, uint16_t port_be, uint8_t out[20]) {
  uint8_t in[8 + 8 + 4 + 2];
  memcpy(in, cky_i, 8);
  memcpy(in + 8, cky_r, 8);
  memcpy(in + 16, ip4, 4);
  util_write_be16(in + 20, port_be);
  mbedtls_sha1_ret(in, sizeof(in), out);
}

static int sockaddr_to_v4(const struct sockaddr *sa, socklen_t salen, uint8_t ip4[4], uint16_t *port_be) {
  (void)salen;
  if (sa->sa_family == AF_INET) {
    const struct sockaddr_in *sin = (const struct sockaddr_in *)sa;
    memcpy(ip4, &sin->sin_addr.s_addr, 4);
    *port_be = sin->sin_port;
    return 0;
  }
  return -1;
}

typedef struct {
  const uint8_t *sa_pkt;
  size_t sa_pkt_len;
  const uint8_t *ke_r;
  size_t ke_r_len;
  const uint8_t *nr;
  size_t nr_len;
  const uint8_t *id_r;
  size_t id_r_len;
  const uint8_t *hash_r;
  size_t hash_r_len;
  uint8_t natd[4][20];
  int natd_count;
} am2_t;

static int parse_payload_chain(const uint8_t *in, int inlen, am2_t *o) {
  memset(o, 0, sizeof(*o));
  if (inlen < 28) return -1;
  const uint8_t *p = in + 28;
  int left = inlen - 28;
  uint8_t np = in[16];
  while (left >= 4 && np != IKE_PT_NONE) {
    uint16_t plen = util_read_be16(p + 2);
    if (plen < 4 || plen > (size_t)left) return -1;
    const uint8_t *body = p + 4;
    size_t blen = plen - 4;
    switch (np) {
      case IKE_PT_SA:
        o->sa_pkt = p;
        o->sa_pkt_len = plen;
        break;
      case IKE_PT_KE:
        o->ke_r = body;
        o->ke_r_len = blen;
        break;
      case IKE_PT_NONCE:
        o->nr = body;
        o->nr_len = blen;
        break;
      case IKE_PT_ID:
        o->id_r = body;
        o->id_r_len = blen;
        break;
      case IKE_PT_HASH:
        o->hash_r = body;
        o->hash_r_len = blen;
        break;
      case IKE_PT_NAT_D:
        if (blen >= 20 && o->natd_count < 4) memcpy(o->natd[o->natd_count++], body, 20);
        break;
      default:
        break;
    }
    np = p[0];
    p += plen;
    left -= (int)plen;
  }
  if (o->ke_r == NULL || o->nr == NULL || o->hash_r == NULL || o->sa_pkt_len == 0) return -1;
  return 0;
}

static int derive_3des_key(const uint8_t skeyid_e[20], uint8_t key24[24]) {
  uint8_t k1[20], k2[20], k3[20];
  uint8_t z = 0;
  if (prf_hmac_sha1(skeyid_e, 20, &z, 1, k1) != 0) return -1;
  if (prf_hmac_sha1(skeyid_e, 20, k1, sizeof(k1), k2) != 0) return -1;
  if (prf_hmac_sha1(skeyid_e, 20, k2, sizeof(k2), k3) != 0) return -1;
  memcpy(key24, k1, 8);
  memcpy(key24 + 8, k2, 8);
  memcpy(key24 + 16, k3, 8);
  return 0;
}

static void phase1_iv_sha1(const uint8_t *gxi, size_t gxi_len, const uint8_t *gxr, size_t gxr_len, uint8_t iv8[8]) {
  mbedtls_sha1_context sha;
  mbedtls_sha1_init(&sha);
  mbedtls_sha1_starts_ret(&sha);
  mbedtls_sha1_update_ret(&sha, gxi, gxi_len);
  mbedtls_sha1_update_ret(&sha, gxr, gxr_len);
  uint8_t h[20];
  mbedtls_sha1_finish_ret(&sha, h);
  mbedtls_sha1_free(&sha);
  memcpy(iv8, h, 8);
}

static void qm_first_iv_sha1(const uint8_t p1_last_block[8], uint32_t msg_id, uint8_t iv8[8]) {
  uint8_t in[12];
  memcpy(in, p1_last_block, 8);
  util_write_be32(in + 8, msg_id);
  mbedtls_sha1_context sha;
  mbedtls_sha1_init(&sha);
  mbedtls_sha1_starts_ret(&sha);
  mbedtls_sha1_update_ret(&sha, in, sizeof(in));
  uint8_t h[20];
  mbedtls_sha1_finish_ret(&sha, h);
  mbedtls_sha1_free(&sha);
  memcpy(iv8, h, 8);
}

static int isakmp_3des_encrypt(const uint8_t key24[24], uint8_t iv_io[8], const uint8_t *plain, size_t plain_len,
                               uint8_t *out, size_t *out_len) {
  size_t pad = 8 - (plain_len % 8);
  if (pad == 0) pad = 8;
  uint8_t buf[512];
  if (plain_len + pad > sizeof(buf)) return -1;
  memcpy(buf, plain, plain_len);
  memset(buf + plain_len, 0, pad);
  size_t tot = plain_len + pad;

  mbedtls_cipher_context_t ciph;
  mbedtls_cipher_init(&ciph);
  const mbedtls_cipher_info_t *info = mbedtls_cipher_info_from_type(MBEDTLS_CIPHER_DES_EDE3_CBC);
  if (info == NULL || mbedtls_cipher_setup(&ciph, info) != 0 ||
      mbedtls_cipher_setkey(&ciph, key24, 192, MBEDTLS_ENCRYPT) != 0 ||
      mbedtls_cipher_set_padding_mode(&ciph, MBEDTLS_PADDING_NONE) != 0) {
    mbedtls_cipher_free(&ciph);
    return -1;
  }
  size_t olen = 0;
  if (mbedtls_cipher_crypt(&ciph, iv_io, 8, buf, tot, out, &olen) != 0) {
    mbedtls_cipher_free(&ciph);
    return -1;
  }
  mbedtls_cipher_free(&ciph);
  memcpy(iv_io, out + olen - 8, 8);
  *out_len = olen;
  return 0;
}

static int isakmp_3des_decrypt(const uint8_t key24[24], const uint8_t iv_in[8], const uint8_t *ct, size_t ct_len,
                               uint8_t *plain, size_t *plain_len, uint8_t iv_out[8]) {
  uint8_t iv[8];
  memcpy(iv, iv_in, 8);
  mbedtls_cipher_context_t ciph;
  mbedtls_cipher_init(&ciph);
  const mbedtls_cipher_info_t *info = mbedtls_cipher_info_from_type(MBEDTLS_CIPHER_DES_EDE3_CBC);
  if (info == NULL || mbedtls_cipher_setup(&ciph, info) != 0 ||
      mbedtls_cipher_setkey(&ciph, key24, 192, MBEDTLS_DECRYPT) != 0 ||
      mbedtls_cipher_set_padding_mode(&ciph, MBEDTLS_PADDING_NONE) != 0) {
    mbedtls_cipher_free(&ciph);
    return -1;
  }
  size_t olen = 0;
  if (mbedtls_cipher_crypt(&ciph, iv, 8, ct, ct_len, plain, &olen) != 0) {
    mbedtls_cipher_free(&ciph);
    return -1;
  }
  mbedtls_cipher_free(&ciph);
  while (olen > 0 && plain[olen - 1] == 0) olen--;
  *plain_len = olen;
  if (ct_len >= 8) memcpy(iv_out, ct + ct_len - 8, 8);
  return 0;
}

static int derive_aes128_key(const uint8_t skeyid_e[20], uint8_t key16[16]) {
  // RFC 2409 sec 5.2: when SKEYID_e (20 bytes) >= key length (16), Ka = first 16 bytes.
  memcpy(key16, skeyid_e, 16);
  return 0;
}

static void phase1_iv_aes128(const uint8_t *gxi, size_t gxil, const uint8_t *gxr, size_t gxrl, uint8_t iv[16]) {
  uint8_t tmp[512];
  if (gxil + gxrl > sizeof(tmp)) {
    memset(iv, 0, 16);
    return;
  }
  memcpy(tmp, gxi, gxil);
  memcpy(tmp + gxil, gxr, gxrl);
  // RFC 2409 sec 5: IV = hash(g^xi | g^xr) using the negotiated hash (SHA-1).
  uint8_t h[20];
  mbedtls_sha1_context sha;
  mbedtls_sha1_init(&sha);
  mbedtls_sha1_starts_ret(&sha);
  mbedtls_sha1_update_ret(&sha, tmp, gxil + gxrl);
  mbedtls_sha1_finish_ret(&sha, h);
  mbedtls_sha1_free(&sha);
  memcpy(iv, h, 16);
}

static void qm_first_iv_aes(const uint8_t p1_last[16], uint32_t mid, uint8_t iv[16]) {
  uint8_t inb[20];
  memcpy(inb, p1_last, 16);
  util_write_be32(inb + 16, mid);
  // RFC 2409 sec 5.3: QM IV = hash(last_p1_msg | MsgID) using negotiated hash (SHA-1).
  uint8_t h[20];
  mbedtls_sha1_context sha;
  mbedtls_sha1_init(&sha);
  mbedtls_sha1_starts_ret(&sha);
  mbedtls_sha1_update_ret(&sha, inb, 20);
  mbedtls_sha1_finish_ret(&sha, h);
  mbedtls_sha1_free(&sha);
  memcpy(iv, h, 16);
}

/* Parse an encrypted Informational exchange after MM5 and detect INVALID_HASH_INFORMATION.
 * Returns: 1 = invalid-hash notify seen, 0 = informational without that notify, -1 = decrypt/parse failure. */
static int info_has_invalid_hash_notify(const uint8_t *pkt, int pkt_len, int p1_aes, const uint8_t *aeskey16,
                                        const uint8_t *deskey24, const uint8_t msg5_last_block[16]) {
  if (pkt == NULL || pkt_len < 28) return -1;
  if (pkt[18] != IKE_EXCH_INFO || (pkt[19] & IKE_FLAG_ENC) == 0) return 0;

  size_t enc_len = (size_t)pkt_len - 28;
  if (enc_len < 8) return -1;

  uint32_t mid = util_read_be32(pkt + 20);
  uint8_t plain[512];
  size_t plain_len = 0;
  uint8_t iv_out[16];
  int dec_ok = 0;

  if (p1_aes) {
    uint8_t iv[16];
    if (mid == 0) {
      memcpy(iv, msg5_last_block, 16);
    } else {
      qm_first_iv_aes(msg5_last_block, mid, iv);
    }
    dec_ok = (isakmp_aes128_decrypt(aeskey16, iv, pkt + 28, enc_len, plain, &plain_len, iv_out) == 0);
  } else {
    uint8_t iv8[8];
    if (mid == 0) {
      memcpy(iv8, msg5_last_block, 8);
    } else {
      qm_first_iv_sha1(msg5_last_block, mid, iv8);
    }
    dec_ok = (isakmp_3des_decrypt(deskey24, iv8, pkt + 28, enc_len, plain, &plain_len, iv_out) == 0);
  }
  if (!dec_ok) return -1;

  uint8_t np = pkt[16];
  size_t walk = 0;
  while (walk + 4 <= plain_len) {
    uint16_t pl = util_read_be16(plain + walk + 2);
    if (pl < 4 || walk + pl > plain_len) return -1;
    const uint8_t *body = plain + walk + 4;
    size_t blen = pl - 4;
    if (np == IKE_PT_NOTIFY && blen >= 8) {
      uint16_t ntype = util_read_be16(body + 6);
      if (ntype == IKE_N_INVALID_HASH_INFORMATION) return 1;
    }
    np = plain[walk];
    walk += pl;
    if (np == IKE_PT_NONE) break;
  }
  return 0;
}

static int isakmp_aes128_encrypt(const uint8_t key16[16], uint8_t iv_io[16], const uint8_t *plain, size_t plain_len,
                                 uint8_t *out, size_t *out_len) {
  size_t pad = 16 - (plain_len % 16);
  if (pad == 0) pad = 16;
  uint8_t buf[512];
  if (plain_len + pad > sizeof(buf)) return -1;
  memcpy(buf, plain, plain_len);
  memset(buf + plain_len, 0, pad);
  size_t tot = plain_len + pad;
  mbedtls_cipher_context_t ciph;
  mbedtls_cipher_init(&ciph);
  const mbedtls_cipher_info_t *info = mbedtls_cipher_info_from_values(MBEDTLS_CIPHER_ID_AES, 128, MBEDTLS_MODE_CBC);
  if (info == NULL || mbedtls_cipher_setup(&ciph, info) != 0 ||
      mbedtls_cipher_setkey(&ciph, key16, 128, MBEDTLS_ENCRYPT) != 0 ||
      mbedtls_cipher_set_padding_mode(&ciph, MBEDTLS_PADDING_NONE) != 0) {
    mbedtls_cipher_free(&ciph);
    return -1;
  }
  uint8_t ivc[16];
  memcpy(ivc, iv_io, 16);
  size_t olen = 0;
  if (mbedtls_cipher_crypt(&ciph, ivc, 16, buf, tot, out, &olen) != 0) {
    mbedtls_cipher_free(&ciph);
    return -1;
  }
  mbedtls_cipher_free(&ciph);
  memcpy(iv_io, out + olen - 16, 16);
  *out_len = olen;
  return 0;
}

static int isakmp_aes128_decrypt(const uint8_t key16[16], const uint8_t iv_in[16], const uint8_t *ct, size_t ct_len,
                                 uint8_t *plain, size_t *plain_len, uint8_t iv_out[16]) {
  uint8_t iv[16];
  memcpy(iv, iv_in, 16);
  mbedtls_cipher_context_t ciph;
  mbedtls_cipher_init(&ciph);
  const mbedtls_cipher_info_t *info = mbedtls_cipher_info_from_values(MBEDTLS_CIPHER_ID_AES, 128, MBEDTLS_MODE_CBC);
  if (info == NULL || mbedtls_cipher_setup(&ciph, info) != 0 ||
      mbedtls_cipher_setkey(&ciph, key16, 128, MBEDTLS_DECRYPT) != 0 ||
      mbedtls_cipher_set_padding_mode(&ciph, MBEDTLS_PADDING_NONE) != 0) {
    mbedtls_cipher_free(&ciph);
    return -1;
  }
  size_t olen = 0;
  if (mbedtls_cipher_crypt(&ciph, iv, 16, ct, ct_len, plain, &olen) != 0) {
    mbedtls_cipher_free(&ciph);
    return -1;
  }
  mbedtls_cipher_free(&ciph);
  if (olen == 0) return -1;
  /* Libreswan (RFC 2409) uses zero-byte padding; strip trailing zeros.
   * The payload parser follows next/length fields so an exact strip is not required. */
  while (olen > 0 && plain[olen - 1] == 0) olen--;
  *plain_len = olen;
  if (ct_len >= 16) memcpy(iv_out, ct + ct_len - 16, 16);
  return 0;
}

static size_t build_p1_sa(uint8_t *b, size_t cap) {
  size_t o = 0;
  if (o + 8 + 120 > cap) return 0;
  util_write_be32(b + o, 1);
  o += 4;
  util_write_be32(b + o, 1);
  o += 4;

  /* Group 0x000e = MODP2048 (RFC 3526), required by default Libreswan l2tp-psk (modp2048). */
  static const uint8_t attrs_aes[] = {0x80, 0x01, 0x00, 0x07, 0x80, 0x0e, 0x00, 0x80, 0x80, 0x02, 0x00, 0x02,
 0x80, 0x03, 0x00, 0x01, 0x80, 0x04, 0x00, 0x0e};
  static const uint8_t attrs_3des[] = {0x80, 0x01, 0x00, 0x05, 0x80, 0x02, 0x00, 0x02, 0x80, 0x03, 0x00, 0x01,
 0x80, 0x04, 0x00, 0x0e};

  /*
   * One ISAKMP Proposal with two Transform payloads (AES then 3DES). Libreswan 5.x rejects
   * two chained Proposal payloads inside the SA ("Proposal must be alone in Oakley SA").
   */
  size_t prop0 = o;
  b[o++] = IKE_PT_NONE;
  b[o++] = 0;
  size_t prop_len_m = o;
  o += 2;
  b[o++] = 1;
  b[o++] = 1;
  b[o++] = 0;
  b[o++] = 2;

  size_t t1s = o;
  b[o++] = IKE_PT_T;
  b[o++] = 0;
  size_t t1_len_m = o;
  o += 2;
  b[o++] = 1;
  b[o++] = 1;
  b[o++] = 0;
  b[o++] = 0;
  memcpy(b + o, attrs_aes, sizeof(attrs_aes));
  o += sizeof(attrs_aes);
  util_write_be16(b + t1_len_m, (uint16_t)(o - t1s));

  size_t t2s = o;
  b[o++] = IKE_PT_NONE;
  b[o++] = 0;
  size_t t2_len_m = o;
  o += 2;
  b[o++] = 2;
  b[o++] = 1;
  b[o++] = 0;
  b[o++] = 0;
  memcpy(b + o, attrs_3des, sizeof(attrs_3des));
  o += sizeof(attrs_3des);
  util_write_be16(b + t2_len_m, (uint16_t)(o - t2s));
  util_write_be16(b + prop_len_m, (uint16_t)(o - prop0));
  return o;
}

// Build Phase 2 ESP SA payload body (DOI + Situation + Proposal + Transform).
// Proposes: ESP_AES (id=12), AES-128-CBC, HMAC-SHA1-96, Transport mode.
// Phase 2 SA attribute types are from the IPSEC DOI (RFC 2407 sec 4.5):
//   type 4 = ENCAPSULATION_MODE (2=Transport)
//   type 5 = AUTH_ALGORITHM    (2=HMAC-SHA1-96)
//   type 6  = KEY_LENGTH        (128 for AES-128)
static size_t build_p2_esp_sa(uint8_t *b, size_t cap, uint32_t spi_be) {
  size_t o = 0;
  if (o + 48 > cap) return 0;
  util_write_be32(b + o, 1); o += 4;  /* DOI = IPSEC (1) */
  util_write_be32(b + o, 1); o += 4;  /* Situation = SIT_IDENTITY_ONLY (1) */
  size_t p0 = o;
  b[o++] = IKE_PT_NONE;  /* next proposal */
  b[o++] = 0;            /* reserved */
  size_t p_len_m = o; o += 2;
  b[o++] = 1;   /* proposal # */
  b[o++] = 3;   /* protocol = ESP */
  b[o++] = 4;   /* SPI size */
  b[o++] = 1;   /* # transforms */
  util_write_be32(b + o, spi_be); o += 4;
  size_t t0 = o;
  b[o++] = 0;   /* next transform = NONE */
  b[o++] = 0;   /* reserved */
  size_t t_len_m = o; o += 2;
  b[o++] = 1;   /* transform # */
  b[o++] = 12;  /* transform ID = ESP_AES (12) */
  b[o++] = 0; b[o++] = 0;  /* reserved */
  // ENCAPSULATION_MODE = UDP-Encap-Transport (type 4, value 4; RFC 3947 sec 6)
  b[o++] = 0x80; b[o++] = 0x04; b[o++] = 0x00; b[o++] = 0x04;
  /* AUTH_ALGORITHM = HMAC-SHA1-96 (type 5, value 2) */
  b[o++] = 0x80; b[o++] = 0x05; b[o++] = 0x00; b[o++] = 0x02;
  /* KEY_LENGTH = 128 bits (IPsec DOI type 6, value 128) */
  b[o++] = 0x80; b[o++] = 0x06; b[o++] = 0x00; b[o++] = 0x80;
  util_write_be16(b + t_len_m, (uint16_t)(o - t0));
  util_write_be16(b + p_len_m, (uint16_t)(o - p0));
  return o;
}

static int nat_need_4500(const am2_t *a, const uint8_t h_us[20], const uint8_t h_peer[20]) {
  if (a->natd_count == 0) return 0;
  int su = 0, sp = 0;
  for (int i = 0; i < a->natd_count; i++) {
    if (memcmp(a->natd[i], h_us, 20) == 0) su = 1;
    if (memcmp(a->natd[i], h_peer, 20) == 0) sp = 1;
  }
  return !(su && sp);
}

/* SA, KE, Nonce, IDii, VID(RFC3947), NAT-D(us), NAT-D(peer) */
static size_t build_am1_ok(uint8_t *pkt, const uint8_t icookie[8], const uint8_t rcookie[8], const uint8_t *sa_body,
                           size_t sa_len, const uint8_t *ke, size_t ke_len, const uint8_t *nonce, size_t nonce_len,
                           const uint8_t *id_b, size_t id_len, const uint8_t *h_us, const uint8_t *h_peer) {
  size_t o = 0;
  memcpy(pkt + o, icookie, 8);
  o += 8;
  memcpy(pkt + o, rcookie, 8);
  o += 8;
  pkt[o++] = IKE_PT_SA;
  pkt[o++] = 0x10;
  pkt[o++] = IKE_EXCH_AGGRESSIVE;
  pkt[o++] = 0;
  util_write_be32(pkt + o, 0);
  o += 4;
  size_t len_m = o;
  o += 4;

  /* Payload 1: SA */
  pkt[o++] = IKE_PT_KE;
  pkt[o++] = 0;
  util_write_be16(pkt + o, (uint16_t)(4 + sa_len));
  o += 2;
  memcpy(pkt + o, sa_body, sa_len);
  o += sa_len;

  /* Payload 2: KE */
  pkt[o++] = IKE_PT_NONCE;
  pkt[o++] = 0;
  util_write_be16(pkt + o, (uint16_t)(4 + ke_len));
  o += 2;
  memcpy(pkt + o, ke, ke_len);
  o += ke_len;

  /* Payload 3: Nonce */
  pkt[o++] = IKE_PT_ID;
  pkt[o++] = 0;
  util_write_be16(pkt + o, (uint16_t)(4 + nonce_len));
  o += 2;
  memcpy(pkt + o, nonce, nonce_len);
  o += nonce_len;

  /* Payload 4: IDii */
  pkt[o++] = IKE_PT_VID;
  pkt[o++] = 0;
  util_write_be16(pkt + o, (uint16_t)(4 + id_len));
  o += 2;
  memcpy(pkt + o, id_b, id_len);
  o += id_len;

  /* Payload 5: VID (RFC 3947 NAT-T) */
  pkt[o++] = IKE_PT_NAT_D;
  pkt[o++] = 0;
  util_write_be16(pkt + o, (uint16_t)(4 + IKE_VID_RFC3947_LEN));
  o += 2;
  memcpy(pkt + o, k_vid_rfc3947, IKE_VID_RFC3947_LEN);
  o += IKE_VID_RFC3947_LEN;

  /* Payload 6: NAT-D (our endpoint, SHA-1) */
  pkt[o++] = IKE_PT_NAT_D;
  pkt[o++] = 0;
  util_write_be16(pkt + o, (uint16_t)(4 + 20));
  o += 2;
  memcpy(pkt + o, h_us, 20);
  o += 20;

  /* Payload 7: NAT-D (peer endpoint, SHA-1) */
  pkt[o++] = IKE_PT_NONE;
  pkt[o++] = 0;
  util_write_be16(pkt + o, (uint16_t)(4 + 20));
  o += 2;
  memcpy(pkt + o, h_peer, 20);
  o += 20;

  util_write_be32(pkt + len_m, (uint32_t)o);
  return o;
}

/* QM2 SA may contain multiple Proposal substructures; some servers echo the initiator SPI
 * before the responder SPI. Outbound ESP must use our spi_i; inbound keymat uses peer SPI.
 * Prefer the last non-zero ESP SPI that differs from our_esp_spi, else fall back to last seen. */
static uint32_t extract_peer_esp_spi_from_qm2_sa(const uint8_t *sa_hdr, size_t sa_pl_len, uint32_t our_esp_spi) {
  if (sa_pl_len < 4 + 12) return 0;
  const uint8_t *b = sa_hdr + 4;
  size_t inner = sa_pl_len - 4;
  if (inner < 12) return 0;
  const uint8_t *p = b + 8;
  size_t left = inner - 8;
  uint32_t last = 0;
  uint32_t peer = 0;
  while (left >= 12) {
    uint16_t plen = util_read_be16(p + 2);
    if (plen < 12 || plen > left) break;
    if (p[5] == 3 && p[6] == 4) {
      uint32_t spi = util_read_be32(p + 8);
      if (spi != 0) {
        last = spi;
        if (spi != our_esp_spi) peer = spi;
      }
    }
    uint8_t np = p[0];
    p += plen;
    left -= plen;
    if (np == 0) break;
  }
  if (peer != 0) return peer;
  if (last == 0) return 0;
  if (last == our_esp_spi) {
    tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG,
                      "Quick Mode SA parse ambiguous: only initiator SPI %08x seen in QM2 SA", (unsigned)our_esp_spi);
    return 0;
  }
  tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG,
                    "Quick Mode SA parse fallback: responder SPI not explicit, using last seen %08x",
                    (unsigned)last);
  return last;
}

/* IKEv1 Quick Mode KEYMAT expansion (RFC 2409 Appendix B):
 * K1 = prf(SKEYID_d, S)
 * K2 = prf(SKEYID_d, K1 | S)
 * K3 = prf(SKEYID_d, K2 | S) ... */
static int keymat_expand_ikev1(const uint8_t *skd, size_t skd_len, const uint8_t *seed, size_t seed_len,
                               uint8_t *out, size_t need) {
  size_t off = 0;
  uint8_t block[20];
  uint8_t prev[20];
  int have_prev = 0;
  while (off < need) {
    uint8_t inb[128];
    if (seed_len > sizeof(inb) || 20 + seed_len > sizeof(inb)) return -1;
    size_t ix = 0;
    if (have_prev) {
      memcpy(inb, prev, 20);
      ix = 20;
    }
    have_prev = 1;
    memcpy(inb + ix, seed, seed_len);
    ix += seed_len;
    if (prf_hmac_sha1(skd, skd_len, inb, ix, block) != 0) return -1;
    memcpy(prev, block, sizeof(block));
    size_t take = 20;
    if (off + take > need) take = need - off;
    memcpy(out + off, block, take);
    off += take;
  }
  return 0;
}

/* --- IKEv1 Main Mode + Quick Mode until ESP keys and udp_encap socket are ready --- */

static int ipsec_negotiate(const char *server, const char *psk, ike_session_t *ike, esp_keys_t *esp) {
  memset(ike, 0, sizeof(*ike));
  memset(esp, 0, sizeof(*esp));
  tunnel_log("IKE begin server=%s psk_len=%zu (PSK not logged)", server, strlen(psk));

  mbedtls_entropy_context entropy;
  mbedtls_ctr_drbg_context ctr;
  mbedtls_entropy_init(&entropy);
  mbedtls_ctr_drbg_init(&ctr);
  const char *pers = "tunnel_forge_ike";
  if (mbedtls_ctr_drbg_seed(&ctr, mbedtls_entropy_func, &entropy, (const uint8_t *)pers, strlen(pers)) != 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: mbedtls_ctr_drbg_seed failed");
    mbedtls_ctr_drbg_free(&ctr);
    mbedtls_entropy_free(&entropy);
    return -1;
  }

  struct sockaddr_storage peer500;
  socklen_t l500 = sizeof(peer500);
  if (resolve_udp(server, IKE_PORT, &peer500, &l500) != 0) {
    mbedtls_ctr_drbg_free(&ctr);
    mbedtls_entropy_free(&entropy);
    return -1;
  }
  ike_log_endpoint("IKE peer:500", (struct sockaddr *)&peer500, l500);

  int fd = socket(peer500.ss_family, SOCK_DGRAM, IPPROTO_UDP);
  if (fd < 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: socket(500) errno=%d", errno);
    goto fail_early;
  }
  if (util_protect_fd(fd) != 0) goto fail_fd;
  if (connect(fd, (struct sockaddr *)&peer500, l500) != 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: connect(500) errno=%d", errno);
    goto fail_fd;
  }

  struct sockaddr_storage lss;
  socklen_t lsl = sizeof(lss);
  if (getsockname(fd, (struct sockaddr *)&lss, &lsl) != 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: getsockname errno=%d", errno);
    goto fail_fd;
  }
  ike_log_endpoint("IKE local endpoint (500)", (struct sockaddr *)&lss, lsl);

  uint8_t ip_us[4], ip_peer[4];
  uint16_t port_us_be, port_peer_be;
  if (sockaddr_to_v4((struct sockaddr *)&lss, lsl, ip_us, &port_us_be) != 0 ||
      sockaddr_to_v4((struct sockaddr *)&peer500, l500, ip_peer, &port_peer_be) != 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IPv4 required for IKE");
    goto fail_fd;
  }

  mbedtls_dhm_context dhm;
  mbedtls_dhm_init(&dhm);
  mbedtls_mpi P, G;
  mbedtls_mpi_init(&P);
  mbedtls_mpi_init(&G);
  if (mbedtls_mpi_read_binary(&P, rfc3526_modp2048_p, sizeof(rfc3526_modp2048_p)) != 0 ||
      mbedtls_mpi_lset(&G, 2) != 0 || mbedtls_dhm_set_group(&dhm, &P, &G) != 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: DH group setup failed");
    mbedtls_mpi_free(&P);
    mbedtls_mpi_free(&G);
    mbedtls_dhm_free(&dhm);
    goto fail_fd;
  }
  mbedtls_mpi_free(&P);
  mbedtls_mpi_free(&G);

  uint8_t pubkey[IKE_DH_PUBKEY_BYTES];
  if (mbedtls_dhm_make_public(&dhm, (int)dhm.len, pubkey, sizeof(pubkey), mbedtls_ctr_drbg_random, &ctr) != 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: dhm_make_public failed");
    mbedtls_dhm_free(&dhm);
    goto fail_fd;
  }

  uint8_t icookie[8];
  mbedtls_ctr_drbg_random(&ctr, icookie, sizeof(icookie));
  memcpy(ike->icookie, icookie, 8);
  memset(ike->rcookie, 0, 8);

  uint8_t ni[32];
  mbedtls_ctr_drbg_random(&ctr, ni, sizeof(ni));

  uint8_t sa_inner[256];
  size_t sa_len = build_p1_sa(sa_inner, sizeof(sa_inner));
  if (sa_len == 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: build_p1_sa failed");
    mbedtls_dhm_free(&dhm);
    goto fail_fd;
  }

  uint8_t id_body[8];
  id_body[0] = 1;
  id_body[1] = id_body[2] = id_body[3] = 0;
  memcpy(id_body + 4, ip_us, 4);

  uint8_t pkt[2048];
  uint8_t in[8192];
  size_t o;
  int inlen;

  struct sockaddr_storage peer_active;
  socklen_t peer_active_len = l500;
  memcpy(&peer_active, &peer500, l500);
  int p1_prefix = 0;

  // IKEv1 Main Mode message 1: SA + NAT-T VID (RFC 3947).
  o = 0;
  memcpy(pkt + o, icookie, 8); o += 8;
  memset(pkt + o, 0, 8);       o += 8;
  pkt[o++] = IKE_PT_SA;
  pkt[o++] = 0x10;
  pkt[o++] = IKE_EXCH_MAIN;
  pkt[o++] = 0;
  util_write_be32(pkt + o, 0); o += 4;
  size_t len_m1 = o; o += 4;
  // SA payload; next payload is VID so Pluto enables NAT-T before MM3.
  pkt[o++] = IKE_PT_VID;
  pkt[o++] = 0;
  util_write_be16(pkt + o, (uint16_t)(4 + sa_len)); o += 2;
  memcpy(pkt + o, sa_inner, sa_len); o += sa_len;
  /* VID payload (RFC 3947 NAT-T) */
  pkt[o++] = IKE_PT_NONE;
  pkt[o++] = 0;
  util_write_be16(pkt + o, (uint16_t)(4 + IKE_VID_RFC3947_LEN)); o += 2;
  memcpy(pkt + o, k_vid_rfc3947, IKE_VID_RFC3947_LEN); o += IKE_VID_RFC3947_LEN;
  util_write_be32(pkt + len_m1, (uint32_t)o);

  tunnel_log("IKE Main Mode msg1 -> %zu bytes (transport=%s)", o, p1_prefix ? "UDP4500+marker" : "UDP500");
  inlen = ike_send_recv(fd, (struct sockaddr *)&peer_active, peer_active_len, pkt, o, in, sizeof(in), 8000, p1_prefix);
  if (inlen < 28) {
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                        "IKE MM1: no reply on UDP 500, retrying via NAT-T UDP 4500 (RFC 3947 non-ESP marker)");
    close(fd);
    fd = -1;
    if (resolve_udp(server, NAT_T_PORT, &peer_active, &peer_active_len) != 0) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: resolve NAT-T :4500 for MM1 fallback failed");
      mbedtls_dhm_free(&dhm);
      goto fail_early;
    }
    ike_log_endpoint("IKE peer:4500 (MM1 fallback)", (struct sockaddr *)&peer_active, peer_active_len);
    fd = socket(peer_active.ss_family, SOCK_DGRAM, IPPROTO_UDP);
    if (fd < 0) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: socket(4500 MM1 fallback) errno=%d", errno);
      mbedtls_dhm_free(&dhm);
      goto fail_early;
    }
    if (util_protect_fd(fd) != 0) {
      mbedtls_dhm_free(&dhm);
      goto fail_fd;
    }
    if (connect(fd, (struct sockaddr *)&peer_active, peer_active_len) != 0) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: connect(4500 MM1 fallback) errno=%d", errno);
      mbedtls_dhm_free(&dhm);
      goto fail_fd;
    }
    lsl = sizeof(lss);
    if (getsockname(fd, (struct sockaddr *)&lss, &lsl) != 0) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: getsockname after MM1 fallback errno=%d", errno);
      mbedtls_dhm_free(&dhm);
      goto fail_fd;
    }
    ike_log_endpoint("IKE local endpoint (MM1 NAT-T path)", (struct sockaddr *)&lss, lsl);
    if (sockaddr_to_v4((struct sockaddr *)&lss, lsl, ip_us, &port_us_be) != 0 ||
        sockaddr_to_v4((struct sockaddr *)&peer_active, peer_active_len, ip_peer, &port_peer_be) != 0) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IPv4 required for IKE (MM1 NAT-T path)");
      mbedtls_dhm_free(&dhm);
      goto fail_fd;
    }
    memcpy(id_body + 4, ip_us, 4);
    p1_prefix = 1;
    esp->udp_encap = 1;
    tunnel_log("IKE Main Mode msg1 retry -> %zu bytes (transport=UDP4500+marker)", o);
    inlen = ike_send_recv(fd, (struct sockaddr *)&peer_active, peer_active_len, pkt, o, in, sizeof(in), 8000, p1_prefix);
  }
  if (inlen < 28) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE MM msg2: no valid reply");
    mbedtls_dhm_free(&dhm);
    goto fail_fd;
  }
  ike_log_isakmp_summary("IKE MM msg2", in, inlen);
  memcpy(ike->rcookie, in + 8, 8);

  // Save the MM2 SA body (selected/accepted SA Libreswan uses for HASH_R).
  // RFC 2409: HASH_I and HASH_R both use SAi_b from MM1, but Libreswan computes HASH_R over the
  // SA body it sent in MM2, so verify HASH_R against this body.
  uint8_t sa2_body[256];
  size_t sa2_body_len = 0;

  ike->p1_aes = 0;
  {
    const uint8_t *mp = in + 28;
    int mleft = inlen - 28;
    uint8_t mnp = in[16];
    while (mleft >= 4 && mnp != IKE_PT_NONE) {
      uint16_t mpl = util_read_be16(mp + 2);
      if (mpl < 4 || mpl > (size_t)mleft) break;
      if (mnp == IKE_PT_SA) {
        for (size_t zi = 0; zi + 4 <= mpl; zi++) {
          if (mp[zi] == 0x80 && mp[zi + 1] == 0x01 &&
              mp[zi + 2] == 0x00 && mp[zi + 3] == 0x07) {
            ike->p1_aes = 1;
            break;
          }
        }
        /* Save SA body (skip the 4-byte generic payload header). */
        size_t body_len = (size_t)mpl - 4;
        if (body_len > 0 && body_len <= sizeof(sa2_body)) {
          memcpy(sa2_body, mp + 4, body_len);
          sa2_body_len = body_len;
        }
      }
      mnp = mp[0];
      mp += mpl;
      mleft -= (int)mpl;
    }
  }
  if (sa2_body_len == 0) {
    /* Fallback: should not happen; use MM1 SA body */
    memcpy(sa2_body, sa_inner, sa_len);
    sa2_body_len = sa_len;
    tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "IKE MM2: SA payload not found, using MM1 SA for HASH_R");
  } else {
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "IKE MM2: saved SA body %zu bytes for HASH_R", sa2_body_len);
  }
  tunnel_log("IKE P1 transform from server: %s", ike->p1_aes ? "AES-128-CBC" : "3DES-CBC");

  /* Refresh local/remote tuple for NAT-D (must match source port Libreswan sees). */
  lsl = sizeof(lss);
  if (getsockname(fd, (struct sockaddr *)&lss, &lsl) != 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: getsockname before MM3 errno=%d", errno);
    mbedtls_dhm_free(&dhm);
    goto fail_fd;
  }
  if (sockaddr_to_v4((struct sockaddr *)&lss, lsl, ip_us, &port_us_be) != 0 ||
      sockaddr_to_v4((struct sockaddr *)&peer_active, peer_active_len, ip_peer, &port_peer_be) != 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IPv4 required for IKE (MM3 NAT-D)");
    mbedtls_dhm_free(&dhm);
    goto fail_fd;
  }
  memcpy(id_body + 4, ip_us, 4);
  tunnel_log("IKE Phase1 transport locked: %s prefix=%d", p1_prefix ? "UDP4500" : "UDP500", p1_prefix);

  // IKEv1 Main Mode message 3: KE, Ni, NAT-D (src), NAT-D (peer).
  // Force NAT-T by hashing a zeroed source IP so the NAT-D check always fails. Android
  // userspace cannot send raw ESP (IP proto 50), so UDP encapsulation on port 4500 is mandatory.
  uint8_t h_us[20], h_peer[20];
  {
    uint8_t fake_ip[4] = {0, 0, 0, 0};
    uint16_t fake_port = 0;
    natd_hash(icookie, ike->rcookie, fake_ip, fake_port, h_us);
  }
  natd_hash(icookie, ike->rcookie, ip_peer, port_peer_be, h_peer);

  o = 0;
  memcpy(pkt + o, ike->icookie, 8); o += 8;
  memcpy(pkt + o, ike->rcookie, 8); o += 8;
  pkt[o++] = IKE_PT_KE;
  pkt[o++] = 0x10;
  pkt[o++] = IKE_EXCH_MAIN;
  pkt[o++] = 0;
  util_write_be32(pkt + o, 0); o += 4;
  size_t len_m3 = o; o += 4;

  pkt[o++] = IKE_PT_NONCE; pkt[o++] = 0;
  util_write_be16(pkt + o, (uint16_t)(4 + sizeof(pubkey))); o += 2;
  memcpy(pkt + o, pubkey, sizeof(pubkey)); o += sizeof(pubkey);

  pkt[o++] = IKE_PT_NAT_D; pkt[o++] = 0;
  util_write_be16(pkt + o, (uint16_t)(4 + sizeof(ni))); o += 2;
  memcpy(pkt + o, ni, sizeof(ni)); o += sizeof(ni);

  pkt[o++] = IKE_PT_NAT_D; pkt[o++] = 0;
  util_write_be16(pkt + o, (uint16_t)(4 + 20)); o += 2;
  memcpy(pkt + o, h_us, 20); o += 20;

  pkt[o++] = IKE_PT_NONE; pkt[o++] = 0;
  util_write_be16(pkt + o, (uint16_t)(4 + 20)); o += 2;
  memcpy(pkt + o, h_peer, 20); o += 20;

  util_write_be32(pkt + len_m3, (uint32_t)o);

  tunnel_log("IKE Main Mode msg3 -> %zu bytes (nat_t_prefix=%d)", o, p1_prefix);
  inlen = ike_send_recv(fd, (struct sockaddr *)&peer_active, peer_active_len, pkt, o, in, sizeof(in), 8000, p1_prefix);
  if (inlen < 28) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE MM msg4: no valid reply");
    mbedtls_dhm_free(&dhm);
    goto fail_fd;
  }
  ike_log_isakmp_summary("IKE MM msg4", in, inlen);

  am2_t am4;
  /* MM4 has only KE + Nonce (no HASH/SA unlike Aggressive Mode AM2).
   * parse_payload_chain returns -1 when hash_r/sa_pkt are absent, so ignore
   * its return value and only validate the fields we actually need. */
  parse_payload_chain(in, inlen, &am4);
  if (am4.ke_r == NULL || am4.ke_r_len == 0 || am4.nr == NULL || am4.nr_len == 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE MM msg4: KE or Nonce missing");
    mbedtls_dhm_free(&dhm);
    goto fail_fd;
  }

  /* am4.ke_r and am4.nr are raw pointers into in[].  That buffer is reused for MM6,
   * so they must be copied out NOW before ike_send_recv() for MM5/MM6 overwrites in[].
   * Without this copy, HASH_R would be computed over MM6 ciphertext bytes, not g^xr. */
  uint8_t ke_r_buf[512];
  size_t  ke_r_len;
  uint8_t nr_buf[256];
  size_t  nr_len;
  if (am4.ke_r_len > sizeof(ke_r_buf) || am4.nr_len > sizeof(nr_buf)) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE MM4: ke_r or nr too large");
    goto fail_fd;
  }
  memcpy(ke_r_buf, am4.ke_r, am4.ke_r_len);
  ke_r_len = am4.ke_r_len;
  memcpy(nr_buf, am4.nr, am4.nr_len);
  nr_len = am4.nr_len;

  // DH shared secret.
  if (mbedtls_dhm_read_public(&dhm, ke_r_buf, ke_r_len) != 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: dhm_read_public failed (bad KE from peer?)");
    mbedtls_dhm_free(&dhm);
    goto fail_fd;
  }
  uint8_t gxy[512];
  size_t gxy_len = 0;
  if (mbedtls_dhm_calc_secret(&dhm, gxy, sizeof(gxy), &gxy_len, mbedtls_ctr_drbg_random, &ctr) != 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: dhm_calc_secret failed");
    mbedtls_dhm_free(&dhm);
    goto fail_fd;
  }
  mbedtls_dhm_free(&dhm);
  strip_leading_zeros(gxy, &gxy_len);

  // SKEYID = prf(PSK, Ni | Nr) (RFC 2409 sec 5, PSK).
  uint8_t skeyid[20];
  {
    uint8_t mat[128];
    if (sizeof(ni) + nr_len > sizeof(mat)) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: SKEYID material too large");
      goto fail_fd;
    }
    memcpy(mat, ni, sizeof(ni));
    memcpy(mat + sizeof(ni), nr_buf, nr_len);
    if (prf_hmac_sha1((const uint8_t *)psk, strlen(psk), mat, sizeof(ni) + nr_len, skeyid) != 0) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: prf SKEYID failed");
      goto fail_fd;
    }
  }

  uint8_t skeyid_d[20], skeyid_a[20], skeyid_e[20];
  {
    uint8_t z0 = 0, z1 = 1, z2 = 2;
    uint8_t m1[8 + 512];
    size_t m1l = 0;
    memcpy(m1 + m1l, gxy, gxy_len); m1l += gxy_len;
    memcpy(m1 + m1l, ike->icookie, 8); m1l += 8;
    memcpy(m1 + m1l, ike->rcookie, 8); m1l += 8;
    m1[m1l++] = z0;
    if (prf_hmac_sha1(skeyid, sizeof(skeyid), m1, m1l, skeyid_d) != 0) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: prf skeyid_d failed");
      goto fail_fd;
    }

    uint8_t m2[20 + 8 + 512];
    size_t m2l = 0;
    memcpy(m2 + m2l, skeyid_d, 20); m2l += 20;
    memcpy(m2 + m2l, gxy, gxy_len); m2l += gxy_len;
    memcpy(m2 + m2l, ike->icookie, 8); m2l += 8;
    memcpy(m2 + m2l, ike->rcookie, 8); m2l += 8;
    m2[m2l++] = z1;
    if (prf_hmac_sha1(skeyid, sizeof(skeyid), m2, m2l, skeyid_a) != 0) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: prf skeyid_a failed");
      goto fail_fd;
    }

    uint8_t m3[20 + 8 + 512];
    size_t m3l = 0;
    memcpy(m3 + m3l, skeyid_a, 20); m3l += 20;
    memcpy(m3 + m3l, gxy, gxy_len); m3l += gxy_len;
    memcpy(m3 + m3l, ike->icookie, 8); m3l += 8;
    memcpy(m3 + m3l, ike->rcookie, 8); m3l += 8;
    m3[m3l++] = z2;
    if (prf_hmac_sha1(skeyid, sizeof(skeyid), m3, m3l, skeyid_e) != 0) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: prf skeyid_e failed");
      goto fail_fd;
    }
  }

  // Derive cipher keys and Phase 1 IV.
  uint8_t deskey[24];
  uint8_t aeskey[16];
  uint8_t p1_iv[16];
  if (ike->p1_aes) {
    if (derive_aes128_key(skeyid_e, aeskey) != 0) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE MM: derive_aes128_key failed");
      goto fail_fd;
    }
    phase1_iv_aes128(pubkey, sizeof(pubkey), ke_r_buf, ke_r_len, p1_iv);
  } else {
    if (derive_3des_key(skeyid_e, deskey) != 0) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE MM: derive_3des_key failed");
      goto fail_fd;
    }
    uint8_t iv8[8];
    phase1_iv_sha1(pubkey, sizeof(pubkey), ke_r_buf, ke_r_len, iv8);
    memcpy(p1_iv, iv8, 8);
    memset(p1_iv + 8, 0, 8);
  }

  /* RFC 2409 uses SAi_b from MM1 for HASH_I/HASH_R. Keep MM2-selected SA as compatibility fallback. */
  const uint8_t *sa_hash_primary = sa_inner;
  size_t sa_hash_primary_len = sa_len;
  const char *sa_hash_primary_tag = "MM1-offered SA";
  const uint8_t *sa_hash_alt = NULL;
  size_t sa_hash_alt_len = 0;
  const char *sa_hash_alt_tag = "";
  if (sa2_body_len && (sa2_body_len != sa_len || memcmp(sa2_body, sa_inner, sa_len) != 0)) {
    sa_hash_alt = sa2_body;
    sa_hash_alt_len = sa2_body_len;
    sa_hash_alt_tag = "MM2-selected SA";
  }

  uint8_t msg5_iv_out[16];
  const uint8_t *sa_hash_used = sa_hash_primary;
  size_t sa_hash_used_len = sa_hash_primary_len;
  const char *sa_hash_used_tag = sa_hash_primary_tag;

  inlen = -1;
  int mm5_attempts = sa_hash_alt ? 2 : 1;
  for (int mm5_try = 0; mm5_try < mm5_attempts; mm5_try++) {
    const uint8_t *sa_hash = (mm5_try == 0) ? sa_hash_primary : sa_hash_alt;
    size_t sa_hash_len = (mm5_try == 0) ? sa_hash_primary_len : sa_hash_alt_len;
    const char *sa_hash_tag = (mm5_try == 0) ? sa_hash_primary_tag : sa_hash_alt_tag;

    // HASH_I = prf(SKEYID, g^xi | g^xr | CKY-I | CKY-R | SAi_b | IDii_b).
    uint8_t hash_i[20];
    {
      size_t hl = sizeof(pubkey) + ke_r_len + 8 + 8 + sa_hash_len + sizeof(id_body);
      uint8_t *hb = malloc(hl ? hl : 1);
      if (!hb) {
        tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: malloc HASH_I material failed");
        goto fail_fd;
      }
      size_t q = 0;
      memcpy(hb + q, pubkey, sizeof(pubkey));
      q += sizeof(pubkey);
      memcpy(hb + q, ke_r_buf, ke_r_len);
      q += ke_r_len;
      memcpy(hb + q, ike->icookie, 8);
      q += 8;
      memcpy(hb + q, ike->rcookie, 8);
      q += 8;
      memcpy(hb + q, sa_hash, sa_hash_len);
      q += sa_hash_len;
      memcpy(hb + q, id_body, sizeof(id_body));
      q += sizeof(id_body);
      tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                        "HASH_I material [%s]: pubkey_len=%zu ke_r_len=%zu sa_len=%zu id_body_len=%zu total=%zu",
                        sa_hash_tag, sizeof(pubkey), ke_r_len, sa_hash_len, sizeof(id_body), q);
      ike_hex_dump("HASH_I sa", sa_hash, sa_hash_len, sa_hash_len);
      ike_hex_dump("HASH_I id_body", id_body, sizeof(id_body), sizeof(id_body));
      if (prf_hmac_sha1(skeyid, sizeof(skeyid), hb, q, hash_i) != 0) {
        free(hb);
        goto fail_fd;
      }
      free(hb);
    }

    // Main Mode message 5: encrypted (IDii + HASH_I).
    uint8_t inner5[64];
    size_t i5 = 0;
    inner5[i5++] = IKE_PT_HASH;
    inner5[i5++] = 0;
    util_write_be16(inner5 + i5, (uint16_t)(4 + sizeof(id_body)));
    i5 += 2;
    memcpy(inner5 + i5, id_body, sizeof(id_body));
    i5 += sizeof(id_body);
    inner5[i5++] = IKE_PT_NONE;
    inner5[i5++] = 0;
    util_write_be16(inner5 + i5, (uint16_t)(4 + sizeof(hash_i)));
    i5 += 2;
    memcpy(inner5 + i5, hash_i, sizeof(hash_i));
    i5 += sizeof(hash_i);

    uint8_t ct5[128];
    size_t ct5l = 0;
    if (ike->p1_aes) {
      uint8_t iv5[16];
      memcpy(iv5, p1_iv, 16);
      if (isakmp_aes128_encrypt(aeskey, iv5, inner5, i5, ct5, &ct5l) != 0) {
        tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE MM msg5: AES encrypt failed");
        goto fail_fd;
      }
      memcpy(msg5_iv_out, iv5, 16);
    } else {
      uint8_t iv5[8];
      memcpy(iv5, p1_iv, 8);
      if (isakmp_3des_encrypt(deskey, iv5, inner5, i5, ct5, &ct5l) != 0) {
        tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE MM msg5: 3DES encrypt failed");
        goto fail_fd;
      }
      memcpy(msg5_iv_out, iv5, 8);
      memset(msg5_iv_out + 8, 0, 8);
    }

    o = 0;
    memcpy(pkt + o, ike->icookie, 8);
    o += 8;
    memcpy(pkt + o, ike->rcookie, 8);
    o += 8;
    pkt[o++] = IKE_PT_ID;
    pkt[o++] = 0x10;
    pkt[o++] = IKE_EXCH_MAIN;
    pkt[o++] = IKE_FLAG_ENC;
    util_write_be32(pkt + o, 0);
    o += 4;
    size_t len_m5 = o;
    o += 4;
    memcpy(pkt + o, ct5, ct5l);
    o += ct5l;
    util_write_be32(pkt + len_m5, (uint32_t)o);

    tunnel_log("IKE Main Mode msg5 (encrypted) try=%d/%d hash_sa=%s len=%zu -> %zu bytes (nat_t_prefix=%d)",
               mm5_try + 1, mm5_attempts, sa_hash_tag, sa_hash_len, o, p1_prefix);
    inlen = ike_send_recv(fd, (struct sockaddr *)&peer_active, peer_active_len, pkt, o, in, sizeof(in), 8000, p1_prefix);
    if (inlen >= 28 && in[18] == IKE_EXCH_MAIN) {
      sa_hash_used = sa_hash;
      sa_hash_used_len = sa_hash_len;
      sa_hash_used_tag = sa_hash_tag;
      break;
    }
    const char *retry_reason = "unexpected_exchange";
    if (inlen < 28) {
      retry_reason = "timeout";
    } else if (in[18] == IKE_EXCH_INFO && (in[19] & IKE_FLAG_ENC)) {
      int info_kind = info_has_invalid_hash_notify(in, inlen, ike->p1_aes, aeskey, deskey, msg5_iv_out);
      if (info_kind == 1) {
        retry_reason = "invalid_hash_notify";
      } else if (info_kind < 0) {
        retry_reason = "info_decrypt_failed";
      } else {
        retry_reason = "informational_notify";
      }
    }
    if (mm5_try + 1 < mm5_attempts &&
        (strcmp(retry_reason, "timeout") == 0 || strcmp(retry_reason, "invalid_hash_notify") == 0)) {
      tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG,
                        "IKE MM msg5: retrying alternate SA hash source after %s (%s)", retry_reason, sa_hash_tag);
      continue;
    }
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG,
                      "IKE MM msg5: aborting after %s (exchange=%u flags=0x%02x inlen=%d)", retry_reason,
                      inlen >= 19 ? in[18] : 0u, inlen >= 20 ? in[19] : 0u, inlen);
    inlen = -1;
    break;
  }
  if (inlen < 28) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE MM msg6: no valid reply after %d msg5 attempt(s)",
                      mm5_attempts);
    goto fail_fd;
  }
  ike_log_isakmp_summary("IKE MM msg6", in, inlen);

  // Decrypt MM6: IDir + HASH_R.
  uint8_t p1_last_block[16];
  {
    size_t enc6_len = (size_t)inlen - 28;
    if (enc6_len < 8) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE MM msg6: ciphertext too short");
      goto fail_fd;
    }
    uint8_t dec6[256];
    size_t dec6_len = 0;
    uint8_t iv6_out[16];
    if (ike->p1_aes) {
      if (isakmp_aes128_decrypt(aeskey, msg5_iv_out, in + 28, enc6_len,
                                dec6, &dec6_len, iv6_out) != 0) {
        tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE MM msg6: AES decrypt failed");
        goto fail_fd;
      }
      memcpy(p1_last_block, iv6_out, 16);
    } else {
      if (isakmp_3des_decrypt(deskey, msg5_iv_out, in + 28, enc6_len,
                              dec6, &dec6_len, iv6_out) != 0) {
        tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE MM msg6: 3DES decrypt failed");
        goto fail_fd;
      }
      memcpy(p1_last_block, iv6_out, 8);
      memset(p1_last_block + 8, 0, 8);
    }
    ike_hex_dump("MM msg6 inner", dec6, dec6_len, 96);

    const uint8_t *id_r = NULL;
    size_t id_r_len = 0;
    const uint8_t *hash_r = NULL;
    size_t hash_r_len = 0;
    {
      size_t walk = 0;
      uint8_t cur = in[16];
      while (walk + 4 <= dec6_len) {
        uint16_t pl = util_read_be16(dec6 + walk + 2);
        if (pl < 4 || walk + pl > dec6_len) break;
        if (cur == IKE_PT_ID) {
          id_r = dec6 + walk + 4;
          id_r_len = pl - 4;
        } else if (cur == IKE_PT_HASH) {
          hash_r = dec6 + walk + 4;
          hash_r_len = pl - 4;
        }
        cur = dec6[walk];
        walk += pl;
        if (cur == IKE_PT_NONE) break;
      }
    }
    if (id_r == NULL || hash_r == NULL || hash_r_len < 20) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE MM msg6: missing IDir or HASH_R");
      goto fail_fd;
    }

    uint8_t hash_r_calc[20];
    {
      // HASH_R = prf(SKEYID, g^xr | g^xi | CKY-R | CKY-I | SAi_b | IDir_b) (RFC 2409 sec 5.1).
      // ke_r_buf is the owned copy of g^xr; am4.ke_r would be stale (points into in[] overwritten by MM6).
      size_t hl = ke_r_len + sizeof(pubkey) + 8 + 8 + sa_hash_used_len + id_r_len;
      uint8_t *hb = malloc(hl ? hl : 1);
      if (!hb) {
        tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: malloc HASH_R material failed");
        goto fail_fd;
      }
      size_t q = 0;
      memcpy(hb + q, ke_r_buf, ke_r_len); q += ke_r_len;
      memcpy(hb + q, pubkey, sizeof(pubkey)); q += sizeof(pubkey);
      memcpy(hb + q, ike->rcookie, 8); q += 8;
      memcpy(hb + q, ike->icookie, 8); q += 8;
      memcpy(hb + q, sa_hash_used, sa_hash_used_len); q += sa_hash_used_len;
      memcpy(hb + q, id_r, id_r_len); q += id_r_len;
      tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
          "HASH_R material [%s]: ke_r_len=%zu pubkey_len=%zu sa_len=%zu id_r_len=%zu total=%zu",
          sa_hash_used_tag, ke_r_len, sizeof(pubkey), sa_hash_used_len, id_r_len, q);
      ike_hex_dump("HASH_R sa", sa_hash_used, sa_hash_used_len, sa_hash_used_len);
      ike_hex_dump("HASH_R id_r", id_r, id_r_len, id_r_len);
      if (prf_hmac_sha1(skeyid, sizeof(skeyid), hb, q, hash_r_calc) != 0) {
        free(hb);
        goto fail_fd;
      }
      free(hb);
    }
    if (memcmp(hash_r_calc, hash_r, 20) != 0) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG,
          "IKE MM: HASH_R mismatch - wrong PSK, ID, or transform");
      ike_hex_dump("hash_r_peer", hash_r, 20, 20);
      ike_hex_dump("hash_r_calc", hash_r_calc, 20, 20);
      goto fail_fd;
    }
    tunnel_log("IKE MM: HASH_R ok (PSK verifies for Phase 1)");
  }

  int use4500;
  int prefix;
  if (p1_prefix) {
    use4500 = 1;
    prefix = 1;
    tunnel_log("NAT-T: Phase 1 already on UDP 4500 (MM1 fallback); Quick Mode will use same socket");
  } else {
    use4500 = nat_need_4500(&am4, h_us, h_peer);
    tunnel_log("NAT-T (UDP 4500) needed=%d natd_payloads=%d", use4500, am4.natd_count);
    memcpy(&peer_active, &peer500, l500);
    peer_active_len = l500;
    prefix = 0;
    if (use4500) {
      close(fd);
      fd = -1;
      if (resolve_udp(server, NAT_T_PORT, &peer_active, &peer_active_len) != 0) {
        tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: resolve NAT-T :4500 failed");
        goto fail_early;
      }
      ike_log_endpoint("IKE peer:4500", (struct sockaddr *)&peer_active, peer_active_len);
      fd = socket(peer_active.ss_family, SOCK_DGRAM, IPPROTO_UDP);
      if (fd < 0) {
        tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: socket(4500) errno=%d", errno);
        goto fail_early;
      }
      if (util_protect_fd(fd) != 0) goto fail_fd;
      if (connect(fd, (struct sockaddr *)&peer_active, peer_active_len) != 0) {
        tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: connect(4500) errno=%d", errno);
        goto fail_fd;
      }
      prefix = 1;
      esp->udp_encap = 1;
      tunnel_engine_log(ANDROID_LOG_INFO, LOG_TAG, "NAT-T: using UDP 4500");
    } else {
      esp->udp_encap = 0;
    }
  }

  size_t len_mark;
  uint32_t qm_mid;
  mbedtls_ctr_drbg_random(&ctr, (uint8_t *)&qm_mid, sizeof(qm_mid));
  if (qm_mid == 0) qm_mid = 1;
  uint32_t spi_i;
  mbedtls_ctr_drbg_random(&ctr, (uint8_t *)&spi_i, sizeof(spi_i));

  uint8_t esp_sa[256];
  size_t esp_sa_len = build_p2_esp_sa(esp_sa, sizeof(esp_sa), spi_i);
  if (esp_sa_len == 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: build_p2_esp_sa failed");
    goto fail_fd;
  }

  uint8_t ni_qm[16];
  mbedtls_ctr_drbg_random(&ctr, ni_qm, sizeof(ni_qm));

  /* ID payloads: [ID_type=1(IPv4), Protocol, Port(BE), IPv4].
   * l2tp-psk uses leftprotoport=17/1701 (server) and rightprotoport=17/%any (client). */
  uint8_t id_ci[8], id_cr[8];
  id_ci[0] = 1; id_ci[1] = 17;               /* IPv4, UDP */
  util_write_be16(id_ci + 2, 0);              /* port = any */
  memcpy(id_ci + 4, ip_us, 4);
  id_cr[0] = 1; id_cr[1] = 17;               /* IPv4, UDP */
  util_write_be16(id_cr + 2, 1701);           /* L2TP port */
  memcpy(id_cr + 4, ip_peer, 4);

  // HASH(1) = prf(SKEYID_a, M-ID | SA | Ni | IDci | IDcr) (RFC 2409 sec 5.5).
  // Each hashed payload includes its 4-byte generic header, matching the QM1 encrypted body
  // (except the HASH payload itself).
  uint8_t hash1_in[256];
  size_t h1l = 0;
  util_write_be32(hash1_in + h1l, qm_mid); h1l += 4;
  /* Full SA payload */
  hash1_in[h1l++] = IKE_PT_NONCE; hash1_in[h1l++] = 0;
  util_write_be16(hash1_in + h1l, (uint16_t)(4 + esp_sa_len)); h1l += 2;
  memcpy(hash1_in + h1l, esp_sa, esp_sa_len); h1l += esp_sa_len;
  /* Full Nonce payload */
  hash1_in[h1l++] = IKE_PT_ID; hash1_in[h1l++] = 0;
  util_write_be16(hash1_in + h1l, (uint16_t)(4 + sizeof(ni_qm))); h1l += 2;
  memcpy(hash1_in + h1l, ni_qm, sizeof(ni_qm)); h1l += sizeof(ni_qm);
  /* Full IDci payload */
  hash1_in[h1l++] = IKE_PT_ID; hash1_in[h1l++] = 0;
  util_write_be16(hash1_in + h1l, (uint16_t)(4 + sizeof(id_ci))); h1l += 2;
  memcpy(hash1_in + h1l, id_ci, sizeof(id_ci)); h1l += sizeof(id_ci);
  /* Full IDcr payload */
  hash1_in[h1l++] = IKE_PT_NONE; hash1_in[h1l++] = 0;
  util_write_be16(hash1_in + h1l, (uint16_t)(4 + sizeof(id_cr))); h1l += 2;
  memcpy(hash1_in + h1l, id_cr, sizeof(id_cr)); h1l += sizeof(id_cr);
  uint8_t hv1[20];
  if (prf_hmac_sha1(skeyid_a, sizeof(skeyid_a), hash1_in, h1l, hv1) != 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE QM: prf HASH(1) failed");
    goto fail_fd;
  }

  uint8_t plain_qm[512];
  size_t pq = 0;
  plain_qm[pq++] = IKE_PT_SA;
  plain_qm[pq++] = 0;
  util_write_be16(plain_qm + pq, (uint16_t)(4 + sizeof(hv1)));
  pq += 2;
  memcpy(plain_qm + pq, hv1, sizeof(hv1));
  pq += sizeof(hv1);
  plain_qm[pq++] = IKE_PT_NONCE;
  plain_qm[pq++] = 0;
  util_write_be16(plain_qm + pq, (uint16_t)(4 + esp_sa_len));
  pq += 2;
  memcpy(plain_qm + pq, esp_sa, esp_sa_len);
  pq += esp_sa_len;
  plain_qm[pq++] = IKE_PT_ID;
  plain_qm[pq++] = 0;
  util_write_be16(plain_qm + pq, (uint16_t)(4 + sizeof(ni_qm)));
  pq += 2;
  memcpy(plain_qm + pq, ni_qm, sizeof(ni_qm));
  pq += sizeof(ni_qm);
  plain_qm[pq++] = IKE_PT_ID;
  plain_qm[pq++] = 0;
  util_write_be16(plain_qm + pq, (uint16_t)(4 + sizeof(id_ci)));
  pq += 2;
  memcpy(plain_qm + pq, id_ci, sizeof(id_ci));
  pq += sizeof(id_ci);
  plain_qm[pq++] = IKE_PT_NONE;
  plain_qm[pq++] = 0;
  util_write_be16(plain_qm + pq, (uint16_t)(4 + sizeof(id_cr)));
  pq += 2;
  memcpy(plain_qm + pq, id_cr, sizeof(id_cr));
  pq += sizeof(id_cr);

  uint8_t ct_qm[768];
  size_t ct_qm_len = 0;
  uint8_t qm1_tail[16];
  if (ike->p1_aes) {
    uint8_t iv_qm[16];
    qm_first_iv_aes(p1_last_block, qm_mid, iv_qm);
    if (isakmp_aes128_encrypt(aeskey, iv_qm, plain_qm, pq, ct_qm, &ct_qm_len) != 0) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE QM: encrypt msg1 AES failed");
      goto fail_fd;
    }
    memcpy(qm1_tail, iv_qm, 16);
  } else {
    uint8_t iv_qm[8];
    qm_first_iv_sha1(p1_last_block, qm_mid, iv_qm);
    if (isakmp_3des_encrypt(deskey, iv_qm, plain_qm, pq, ct_qm, &ct_qm_len) != 0) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE QM: encrypt msg1 3DES failed");
      goto fail_fd;
    }
    memcpy(qm1_tail, iv_qm, 8);
    memset(qm1_tail + 8, 0, 8);
  }

  o = 0;
  memcpy(pkt + o, ike->icookie, 8);
  o += 8;
  memcpy(pkt + o, ike->rcookie, 8);
  o += 8;
  pkt[o++] = IKE_PT_HASH;   /* first encrypted payload is HASH(1) */
  pkt[o++] = 0x10;
  pkt[o++] = IKE_EXCH_QUICK;
  pkt[o++] = IKE_FLAG_ENC;
  util_write_be32(pkt + o, qm_mid);
  o += 4;
  len_mark = o;
  o += 4;
  memcpy(pkt + o, ct_qm, ct_qm_len);
  o += ct_qm_len;
  util_write_be32(pkt + len_mark, (uint32_t)o);

  tunnel_log("IKE Quick Mode msg1 -> %zu bytes (nat_t=%d)", o, prefix);
  inlen = ike_send_recv(fd, (struct sockaddr *)&peer_active, peer_active_len, pkt, o, in, sizeof(in), 10000, prefix);
  if (inlen < 28) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "Quick Mode: no reply (timeout or send error; see ike_send_recv)");
    goto fail_fd;
  }
  ike_log_isakmp_summary("Quick Mode reply (msg2)", in, inlen);

  if (in[18] == IKE_EXCH_INFO && (in[19] & IKE_FLAG_ENC)) {
    uint32_t info_mid = util_read_be32(in + 20);
    size_t info_enc_len = (size_t)inlen - 28;
    if (info_enc_len >= 16) {
      uint8_t info_plain[256];
      size_t info_plain_len = 0;
      uint8_t info_iv_out[16];
      uint8_t info_iv[16];
      if (ike->p1_aes) {
        qm_first_iv_aes(p1_last_block, info_mid, info_iv);
        isakmp_aes128_decrypt(aeskey, info_iv, in + 28, info_enc_len,
                              info_plain, &info_plain_len, info_iv_out);
      } else {
        uint8_t iv8[8];
        qm_first_iv_sha1(p1_last_block, info_mid, iv8);
        memcpy(info_iv, iv8, 8);
        isakmp_3des_decrypt(deskey, info_iv, in + 28, info_enc_len,
                            info_plain, &info_plain_len, info_iv_out);
      }
      uint8_t info_np = in[16];
      size_t w = 0;
      while (w + 4 <= info_plain_len) {
        uint16_t pl = util_read_be16(info_plain + w + 2);
        if (pl < 4 || w + pl > info_plain_len) break;
        if (info_np == IKE_PT_NOTIFY && pl >= 12) {
          uint16_t ntype = util_read_be16(info_plain + w + 4 + 6);
          tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG,
              "Quick Mode: server sent NOTIFY type=%u (0x%04x) - proposal rejected",
              (unsigned)ntype, (unsigned)ntype);
        }
        info_np = info_plain[w];
        w += pl;
        if (info_np == IKE_PT_NONE) break;
      }
    }
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG,
        "Quick Mode: server replied with Informational Exchange (not QM2); ESP proposal likely rejected");
    goto fail_fd;
  }

  if ((in[19] & IKE_FLAG_ENC) == 0 || util_read_be32(in + 20) != qm_mid) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG,
 "Quick Mode: unexpected reply flags/msgid (want enc+mid=%u got flags=0x%02x mid=%u)", qm_mid, in[19],
                        util_read_be32(in + 20));
    goto fail_fd;
  }

  size_t enc_len = (size_t)inlen - 28;
  if (enc_len < 8) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "Quick Mode: ciphertext too short enc_len=%zu", enc_len);
    goto fail_fd;
  }
  uint8_t dec_qm[768];
  size_t dec_len = 0;
  uint8_t qm2_iv_out[16];
  if (ike->p1_aes) {
    if (isakmp_aes128_decrypt(aeskey, qm1_tail, in + 28, enc_len, dec_qm, &dec_len, qm2_iv_out) != 0) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "Quick Mode: AES decrypt msg2 failed (wrong P1 key/IV?)");
      goto fail_fd;
    }
  } else {
    if (isakmp_3des_decrypt(deskey, qm1_tail, in + 28, enc_len, dec_qm, &dec_len, qm2_iv_out) != 0) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "Quick Mode: 3DES decrypt msg2 failed");
      goto fail_fd;
    }
    memset(qm2_iv_out + 8, 0, 8);
  }
  tunnel_log("Quick Mode msg2 plaintext parse len=%zu", dec_len);
  ike_hex_dump("QM msg2 inner", dec_qm, dec_len, 96);

  const uint8_t *nr_qm = NULL;
  size_t nr_qm_len = 0;
  const uint8_t *hash2 = NULL;
  size_t hash2_len = 0;
  size_t after_hash_off = 0;
  size_t qm2_payload_end = 0;
  uint32_t spi_r = 0;
  int qm2_chain_terminated = 0;
  int qm2_chain_malformed = 0;
  {
    size_t walk = 0;
    uint8_t cur = (inlen >= 17) ? in[16] : IKE_PT_HASH;
    while (walk + 4 <= dec_len && cur != IKE_PT_NONE) {
      uint16_t pl = util_read_be16(dec_qm + walk + 2);
      if (pl < 4 || walk + pl > dec_len) {
        qm2_chain_malformed = 1;
        break;
      }
      const uint8_t *bd = dec_qm + walk + 4;
      size_t bl = pl - 4;
      if (cur == IKE_PT_HASH && hash2 == NULL) {
        hash2 = bd;
        hash2_len = bl;
        after_hash_off = walk + pl;
      } else if (cur == IKE_PT_SA) {
        spi_r = extract_peer_esp_spi_from_qm2_sa(dec_qm + walk, pl, spi_i);
      } else if (cur == IKE_PT_NONCE && nr_qm == NULL) {
        nr_qm = bd;
        nr_qm_len = bl;
      }
      uint8_t next = dec_qm[walk];
      walk += pl;
      if (next == IKE_PT_NONE) {
        qm2_payload_end = walk;
        qm2_chain_terminated = 1;
        break;
      }
      cur = next;
    }
    if (!qm2_chain_terminated && !qm2_chain_malformed) {
      if (walk >= dec_len) qm2_chain_malformed = 1;
    }
  }
  if (!qm2_chain_terminated || qm2_payload_end == 0 || qm2_chain_malformed) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG,
                      "Quick Mode: malformed payload chain terminated=%d malformed=%d payload_end=%zu dec_len=%zu",
                      qm2_chain_terminated, qm2_chain_malformed, qm2_payload_end, dec_len);
    goto fail_fd;
  }
  if (nr_qm == NULL || hash2 == NULL || spi_r == 0 || after_hash_off == 0 || after_hash_off >= qm2_payload_end) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG,
                      "Quick Mode: missing required payloads nr=%p hash2=%p spi_r=%08x spi_i=%08x after_hash=%zu payload_end=%zu",
                      (void *)nr_qm, (void *)hash2, (unsigned)spi_r, (unsigned)spi_i, after_hash_off,
                      qm2_payload_end);
    goto fail_fd;
  }
  if (nr_qm_len == 0 || nr_qm_len > 64) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "Quick Mode: unsupported Nr length=%zu", nr_qm_len);
    goto fail_fd;
  }
  tunnel_log("Quick Mode: peer spi_r=%08x nr_len=%zu", (unsigned)spi_r, nr_qm_len);

  // HASH(2) = prf(SKEYID_a, M-ID | Ni_b | <all payloads after HASH in QM2, with headers>).
  // RFC 2409 sec 5.5: message id concatenated with the entire message after HASH, including
  // payload headers. Ni_b is prepended for HASH(2).
  size_t after_hash_len = qm2_payload_end - after_hash_off;
  uint8_t *h2chk = malloc(4 + sizeof(ni_qm) + after_hash_len);
  if (!h2chk) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "Quick Mode: malloc HASH(2) material failed");
    goto fail_fd;
  }
  size_t h2l = 0;
  util_write_be32(h2chk + h2l, qm_mid); h2l += 4;
  memcpy(h2chk + h2l, ni_qm, sizeof(ni_qm)); h2l += sizeof(ni_qm);
  memcpy(h2chk + h2l, dec_qm + after_hash_off, after_hash_len); h2l += after_hash_len;
  uint8_t h2c[20];
  if (prf_hmac_sha1(skeyid_a, sizeof(skeyid_a), h2chk, h2l, h2c) != 0) {
    free(h2chk);
    goto fail_fd;
  }
  free(h2chk);
  if (hash2_len < 20) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "Quick Mode: HASH(2) payload too short len=%zu", hash2_len);
    goto fail_fd;
  }
  if (memcmp(h2c, hash2, 20) != 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "Quick Mode: HASH(2) mismatch (ESP proposal rejected?)");
    tunnel_log("Quick Mode: HASH(2) material after_hash=%zu payload_end=%zu material_len=%zu",
               after_hash_off, qm2_payload_end, after_hash_len);
    ike_hex_dump("hash2_peer", hash2, hash2_len < 20 ? hash2_len : 20, 20);
    ike_hex_dump("hash2_calc", h2c, 20, 20);
    goto fail_fd;
  }
  tunnel_log("Quick Mode: HASH(2) ok");

  uint8_t h3in[1 + 4 + 16 + 64];
  size_t h3l = 0;
  h3in[h3l++] = 0;
  util_write_be32(h3in + h3l, qm_mid);
  h3l += 4;
  memcpy(h3in + h3l, ni_qm, sizeof(ni_qm));
  h3l += sizeof(ni_qm);
  memcpy(h3in + h3l, nr_qm, nr_qm_len);
  h3l += nr_qm_len;
  uint8_t h3[20];
  if (prf_hmac_sha1(skeyid_a, sizeof(skeyid_a), h3in, h3l, h3) != 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "Quick Mode: prf HASH(3) failed");
    goto fail_fd;
  }

  uint8_t inner3[64];
  size_t i3 = 0;
  inner3[i3++] = IKE_PT_NONE;
  inner3[i3++] = 0;
  util_write_be16(inner3 + i3, (uint16_t)(4 + sizeof(h3)));
  i3 += 2;
  memcpy(inner3 + i3, h3, sizeof(h3));
  i3 += sizeof(h3);
  uint8_t ct3b[64];
  size_t c3l = 0;
  if (ike->p1_aes) {
    uint8_t iv_qm3[16];
    memcpy(iv_qm3, qm2_iv_out, 16);
    if (isakmp_aes128_encrypt(aeskey, iv_qm3, inner3, i3, ct3b, &c3l) != 0) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "Quick Mode: encrypt msg3 AES failed");
      goto fail_fd;
    }
  } else {
    uint8_t iv_qm3[8];
    memcpy(iv_qm3, qm2_iv_out, 8);
    if (isakmp_3des_encrypt(deskey, iv_qm3, inner3, i3, ct3b, &c3l) != 0) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "Quick Mode: encrypt msg3 3DES failed");
      goto fail_fd;
    }
  }

  o = 0;
  memcpy(pkt + o, ike->icookie, 8);
  o += 8;
  memcpy(pkt + o, ike->rcookie, 8);
  o += 8;
  pkt[o++] = IKE_PT_HASH;   /* first encrypted payload is HASH(3) */
  pkt[o++] = 0x10;
  pkt[o++] = IKE_EXCH_QUICK;
  pkt[o++] = IKE_FLAG_ENC;
  util_write_be32(pkt + o, qm_mid);
  o += 4;
  len_mark = o;
  o += 4;
  memcpy(pkt + o, ct3b, c3l);
  o += c3l;
  util_write_be32(pkt + len_mark, (uint32_t)o);
  tunnel_log("IKE Quick Mode msg3 -> %zu bytes", o);
  if (ike_qm3_finish_recv_loop(fd, (struct sockaddr *)&peer_active, peer_active_len, pkt, o, in, sizeof(in), prefix,
                               qm_mid, ike->p1_aes, aeskey, deskey, qm1_tail) != 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "Quick Mode msg3: send or recv loop failed");
    goto fail_fd;
  }

  if (!use4500) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG,
        "IKE: NAT-T was not negotiated; raw ESP requires root (impossible on Android). "
        "This should not happen with forced NAT-D.");
    goto fail_fd;
  }

  /* KEYMAT interop matrix (A/B/C):
   * A: per-SPI seeds (IKEv1-style expansion), B/C: single-stream split variants. */
  uint8_t keymat_a_i[36], keymat_a_r[36];
  uint8_t keymat_b_i[36], keymat_b_r[36];
  uint8_t keymat_c_i[36], keymat_c_r[36];
  {
    /* Variant A: per-SA, seed = proto|spi|Ni|Nr */
    uint8_t seed_r[1 + 4 + 16 + 64], seed_i[1 + 4 + 16 + 64];
    size_t sl_r = 0, sl_i = 0;
    seed_r[sl_r++] = 3;
    util_write_be32(seed_r + sl_r, spi_r);
    sl_r += 4;
    memcpy(seed_r + sl_r, ni_qm, sizeof(ni_qm));
    sl_r += sizeof(ni_qm);
    memcpy(seed_r + sl_r, nr_qm, nr_qm_len);
    sl_r += nr_qm_len;
    seed_i[sl_i++] = 3;
    util_write_be32(seed_i + sl_i, spi_i);
    sl_i += 4;
    memcpy(seed_i + sl_i, ni_qm, sizeof(ni_qm));
    sl_i += sizeof(ni_qm);
    memcpy(seed_i + sl_i, nr_qm, nr_qm_len);
    sl_i += nr_qm_len;
    if (keymat_expand_ikev1(skeyid_d, sizeof(skeyid_d), seed_r, sl_r, keymat_a_r, sizeof(keymat_a_r)) != 0 ||
        keymat_expand_ikev1(skeyid_d, sizeof(skeyid_d), seed_i, sl_i, keymat_a_i, sizeof(keymat_a_i)) != 0) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: keymat expansion variant A failed");
      goto fail_fd;
    }
  }
  {
    /* Variant B: one stream, seed = proto|spi_i|spi_r|Ni|Nr, split to i/r blocks */
    uint8_t seed[1 + 4 + 4 + 16 + 64];
    size_t sl = 0;
    seed[sl++] = 3;
    util_write_be32(seed + sl, spi_i);
    sl += 4;
    util_write_be32(seed + sl, spi_r);
    sl += 4;
    memcpy(seed + sl, ni_qm, sizeof(ni_qm));
    sl += sizeof(ni_qm);
    memcpy(seed + sl, nr_qm, nr_qm_len);
    sl += nr_qm_len;
    uint8_t stream[72];
    if (keymat_expand_ikev1(skeyid_d, sizeof(skeyid_d), seed, sl, stream, sizeof(stream)) != 0) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: keymat expansion variant B failed");
      goto fail_fd;
    }
    memcpy(keymat_b_i, stream, 36);
    memcpy(keymat_b_r, stream + 36, 36);
  }
  {
    /* Variant C: one stream, seed = proto|spi_i|spi_r|Nr|Ni, split to i/r blocks */
    uint8_t seed[1 + 4 + 4 + 16 + 64];
    size_t sl = 0;
    seed[sl++] = 3;
    util_write_be32(seed + sl, spi_i);
    sl += 4;
    util_write_be32(seed + sl, spi_r);
    sl += 4;
    memcpy(seed + sl, nr_qm, nr_qm_len);
    sl += nr_qm_len;
    memcpy(seed + sl, ni_qm, sizeof(ni_qm));
    sl += sizeof(ni_qm);
    uint8_t stream[72];
    if (keymat_expand_ikev1(skeyid_d, sizeof(skeyid_d), seed, sl, stream, sizeof(stream)) != 0) {
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE: keymat expansion variant C failed");
      goto fail_fd;
    }
    memcpy(keymat_c_i, stream, 36);
    memcpy(keymat_c_r, stream + 36, 36);
  }

  const uint8_t *keymat_i = keymat_a_i;
  const uint8_t *keymat_r = keymat_a_r;
  const char *keymat_variant = "A";
  enum { KEYMAT_VARIANT_A = 1, KEYMAT_VARIANT_B = 2, KEYMAT_VARIANT_C = 3 };
  const int active_keymat_variant = TUNNEL_FORGE_KEYMAT_VARIANT;
  if (active_keymat_variant == KEYMAT_VARIANT_B) {
    keymat_i = keymat_b_i;
    keymat_r = keymat_b_r;
    keymat_variant = "B";
  } else if (active_keymat_variant == KEYMAT_VARIANT_C) {
    keymat_i = keymat_c_i;
    keymat_r = keymat_c_r;
    keymat_variant = "C";
  }

  /* Libreswan transport-mode SA directions are opposite of local "initiator/responder" labels:
   * client outbound must use the peer's inbound SPI/material from QM2. */
  esp->spi_i = spi_r;
  esp->spi_r = spi_i;
  esp->enc_key_len = 16;
  esp->auth_key_len = 20;
  memcpy(esp->ip_src, ip_us, 4);
  memcpy(esp->ip_dst, ip_peer, 4);
  // Outbound (us->peer): use responder material (keymat_r / spi_r from QM2 parse).
  memcpy(esp->enc_key,      keymat_r,      16);
  memcpy(esp->auth_key,     keymat_r + 16, 20);
  // Inbound (peer->us): use initiator material (keymat_i / spi_i we proposed).
  memcpy(esp->enc_key  + 16, keymat_i,      16);
  memcpy(esp->auth_key + 20, keymat_i + 16, 20);
  esp->seq_i = 1;
  esp->replay_bitmap = 0;
  esp->replay_top = 0;
  esp->outbound_profile = 0;
  if (s_ike_keymat_variants_log_once == 0) {
    s_ike_keymat_variants_log_once = 1;
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                      "IKE keymat probe: build=kmat-v3 macro=%d active=%s spi_i=%08x spi_r=%08x",
                      active_keymat_variant, keymat_variant, (unsigned)spi_i, (unsigned)spi_r);
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                      "IKE keymat A: out=%02x%02x%02x%02x/%02x%02x%02x%02x in=%02x%02x%02x%02x/%02x%02x%02x%02x",
                      keymat_a_r[0], keymat_a_r[1], keymat_a_r[2], keymat_a_r[3], keymat_a_r[16], keymat_a_r[17],
                      keymat_a_r[18], keymat_a_r[19], keymat_a_i[0], keymat_a_i[1], keymat_a_i[2], keymat_a_i[3],
                      keymat_a_i[16], keymat_a_i[17], keymat_a_i[18], keymat_a_i[19]);
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "IKE keymat A K1/K2 out=%02x%02x%02x%02x/%02x%02x%02x%02x",
                      keymat_a_r[0], keymat_a_r[1], keymat_a_r[2], keymat_a_r[3],
                      keymat_a_r[20], keymat_a_r[21], keymat_a_r[22], keymat_a_r[23]);
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                      "IKE keymat B: out=%02x%02x%02x%02x/%02x%02x%02x%02x in=%02x%02x%02x%02x/%02x%02x%02x%02x",
                      keymat_b_r[0], keymat_b_r[1], keymat_b_r[2], keymat_b_r[3], keymat_b_r[16], keymat_b_r[17],
                      keymat_b_r[18], keymat_b_r[19], keymat_b_i[0], keymat_b_i[1], keymat_b_i[2], keymat_b_i[3],
                      keymat_b_i[16], keymat_b_i[17], keymat_b_i[18], keymat_b_i[19]);
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "IKE keymat B K1/K2 out=%02x%02x%02x%02x/%02x%02x%02x%02x",
                      keymat_b_r[0], keymat_b_r[1], keymat_b_r[2], keymat_b_r[3],
                      keymat_b_r[20], keymat_b_r[21], keymat_b_r[22], keymat_b_r[23]);
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                      "IKE keymat C: out=%02x%02x%02x%02x/%02x%02x%02x%02x in=%02x%02x%02x%02x/%02x%02x%02x%02x",
                      keymat_c_r[0], keymat_c_r[1], keymat_c_r[2], keymat_c_r[3], keymat_c_r[16], keymat_c_r[17],
                      keymat_c_r[18], keymat_c_r[19], keymat_c_i[0], keymat_c_i[1], keymat_c_i[2], keymat_c_i[3],
                      keymat_c_i[16], keymat_c_i[17], keymat_c_i[18], keymat_c_i[19]);
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "IKE keymat C K1/K2 out=%02x%02x%02x%02x/%02x%02x%02x%02x",
                      keymat_c_r[0], keymat_c_r[1], keymat_c_r[2], keymat_c_r[3],
                      keymat_c_r[20], keymat_c_r[21], keymat_c_r[22], keymat_c_r[23]);
  }

#if defined(TUNNEL_FORGE_DEBUG_ESP_KEYMAT) && TUNNEL_FORGE_DEBUG_ESP_KEYMAT
  tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                    "DEBUG_ESP_KEYMAT: spi_i=%08x spi_r=%08x variant=%s - compare keymat_* to gateway `ip xfrm state` (enc+auth)",
                    (unsigned)spi_i, (unsigned)spi_r, keymat_variant);
  ike_hex_dump("keymat_i out(enc|auth)", keymat_i, 36, 36);
  ike_hex_dump("keymat_r in(enc|auth)", keymat_r, 36, 36);
#endif
  if (s_ike_keymap_log_once == 0) {
    s_ike_keymap_log_once = 1;
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                      "IKE keymap one-shot: client_out_spi=%08x client_in_spi=%08x out_enc=%02x%02x%02x%02x out_auth=%02x%02x%02x%02x in_enc=%02x%02x%02x%02x in_auth=%02x%02x%02x%02x",
                      (unsigned)esp->spi_i, (unsigned)esp->spi_r,
                      esp->enc_key[0], esp->enc_key[1], esp->enc_key[2], esp->enc_key[3],
                      esp->auth_key[0], esp->auth_key[1], esp->auth_key[2], esp->auth_key[3],
                      esp->enc_key[16], esp->enc_key[17], esp->enc_key[18], esp->enc_key[19],
                      esp->auth_key[20], esp->auth_key[21], esp->auth_key[22], esp->auth_key[23]);
  }

  memcpy(&ike->peer, &peer_active, peer_active_len);
  ike->peer_len = peer_active_len;
  ike->esp_fd = fd;

  memcpy(ike->skeyid, skeyid, sizeof(skeyid));
  ike->skeyid_len = sizeof(skeyid);
  memcpy(ike->skeyid_d, skeyid_d, sizeof(skeyid_d));
  ike->skeyid_d_len = sizeof(skeyid_d);
  memcpy(ike->skeyid_a, skeyid_a, sizeof(skeyid_a));
  ike->skeyid_a_len = sizeof(skeyid_a);
  memcpy(ike->skeyid_e, skeyid_e, sizeof(skeyid_e));
  ike->skeyid_e_len = sizeof(skeyid_e);
  ike->nat_t = use4500 ? 1 : 0;

  mbedtls_ctr_drbg_free(&ctr);
  mbedtls_entropy_free(&entropy);
  tunnel_log("IKE+QM ok spi_i=%08x spi_r=%08x udp_encap=%d", (unsigned)spi_i, (unsigned)spi_r, esp->udp_encap);
  tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                    "IKE QM SA map: outbound_spi=%08x inbound_spi=%08x profile=%s",
                    (unsigned)esp->spi_i, (unsigned)esp->spi_r, esp->outbound_profile ? "alt-direction" : "primary");
  tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                      "IKE QM keys: inner_ip_src=%02x%02x%02x%02x inner_ip_dst=%02x%02x%02x%02x (for ESP inner UDP pseudo-hdr)",
                      ip_us[0], ip_us[1], ip_us[2], ip_us[3], ip_peer[0], ip_peer[1], ip_peer[2], ip_peer[3]);
  ike_log_endpoint("IKE QM peer (NAT-T/ESP)", (struct sockaddr *)&peer_active, peer_active_len);
  return 0;

fail_fd:
  if (fd >= 0) close(fd);
fail_early:
  tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "IKE ipsec_negotiate failed (scroll up for tunnel_engine)");
  mbedtls_ctr_drbg_free(&ctr);
  mbedtls_entropy_free(&entropy);
  return -1;
}

/* --- Public entry: cleartext L2TP shortcut vs full IPsec negotiation --- */

int ikev1_connect(const char *server, const char *psk, ike_session_t *ike, esp_keys_t *esp) {
  /* Empty PSK = L2TP-only (no IPsec). */
  if (psk == NULL || psk[0] == '\0') {
    tunnel_log("ikev1_connect: empty PSK -> cleartext L2TP (no IPsec)");
    return cleartext_l2tp(server, ike, esp);
  }
  tunnel_log("ikev1_connect: IPsec+IKE path psk_len=%zu", strlen(psk));
  return ipsec_negotiate(server, psk, ike, esp);
}
