/*
 * L2TPv2 over IPsec or cleartext UDP (RFC 2661): control-plane handshake (SCCRQ…ICCN), then
 * data-channel demux (PPP inside L2TP). Shares the ESP fd with IKE during setup; recv_plain
 * filters NAT-T keepalives and stray ISAKMP on UDP/4500.
 */
#include "l2tp.h"

#include "engine.h"
#include "esp_udp.h"
#include "l2tp_avps.h"
#include "nat_t_keepalive.h"
#include "ppp.h"

#include <android/log.h>
#include <errno.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define LOG_TAG "tunnel_engine"

/** INFO logs for raw recv hex during L2TP handshake (reset in [l2tp_handshake](l2tp.c)). */
static int s_recv_plain_info_budget;

/** Debug breadcrumbs during handshake only ([l2tp_handshake](l2tp.c)); filter `adb logcat -s tunnel_engine:D`. */
static unsigned s_l2tp_hs_trace_rem;

static void l2tp_hs_trace_evt(const char *evt, const uint8_t *raw, ssize_t n) {
  if (s_l2tp_hs_trace_rem == 0) return;
  s_l2tp_hs_trace_rem--;
  unsigned a = n > 0 ? raw[0] : 0, b = n > 1 ? raw[1] : 0, c = n > 2 ? raw[2] : 0, d = n > 3 ? raw[3] : 0;
  unsigned e = n > 4 ? raw[4] : 0, f = n > 5 ? raw[5] : 0, g = n > 6 ? raw[6] : 0, h = n > 7 ? raw[7] : 0;
  tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                    "l2tp hs trace: %s n=%zd %02x%02x%02x%02x%02x%02x%02x%02x", evt, n, a, b, c, d, e, f, g, h);
}

/** Same UDP/4500 socket carries IKE (ISAKMP) and ESP; do not send ISAKMP to ESP. */
static int isakmp_datagram_like(const uint8_t *p, size_t len) {
  if (len < 28) return 0;
  /* Octet 17: major/minor (0x10 = IKEv1 v1.0); length at 24-27 must span whole datagram. */
  if (p[17] != 0x10) return 0;
  uint32_t isakmp_len = util_read_be32(p + 24);
  return isakmp_len >= 28 && isakmp_len == (uint32_t)len;
}

typedef struct recv_plain_stats {
  unsigned rx_datagrams;
  unsigned skip_keepalive;
  unsigned skip_ike;
  unsigned decrypt_try_primary;
  unsigned decrypt_try_alt;
  unsigned decrypt_ok_primary;
  unsigned decrypt_ok_alt;
  unsigned decrypt_fail_primary;
  unsigned decrypt_fail_alt;
} recv_plain_stats_t;

static void recv_plain_log_stats(const char *ctx, const recv_plain_stats_t *st, int elapsed_ms, int timeout_ms) {
  tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                    "l2tp recv_plain stats [%s]: rx=%u keepalive=%u ike=%u dec_try_p=%u dec_try_alt=%u dec_ok_p=%u dec_ok_alt=%u dec_fail_p=%u dec_fail_alt=%u elapsed=%d timeout=%d",
                    ctx, st->rx_datagrams, st->skip_keepalive, st->skip_ike, st->decrypt_try_primary,
                    st->decrypt_try_alt, st->decrypt_ok_primary, st->decrypt_ok_alt, st->decrypt_fail_primary,
                    st->decrypt_fail_alt, elapsed_ms, timeout_ms);
}

/**
 * Receive one plain L2TP payload from the shared UDP/4500 socket.
 *
 * Data flow summary:
 * - Poll the socket in short slices until timeout/cancel/data.
 * - Ignore NAT-T keepalives and ISAKMP/IKE datagrams that must not enter ESP decrypt.
 * - Accept either cleartext payloads (when ESP decrypt is disabled) or ESP-decrypted payloads.
 * - Try primary keys first, then optional alternate keys, and report which keyset succeeded.
 *
 * Ownership/parameter assumptions:
 * - @p out points to caller-owned storage of size @p out_cap.
 * - @p esp is required; @p esp_alt and @p used_alt are optional.
 * - @p used_alt is reset to 0 on entry and set to 1 only when alternate keys decrypt successfully.
 *
 * Loop/timeout and cancellation:
 * - Uses an overall timeout budget (@p timeout_ms) plus a hard safety cap of 256 iterations.
 * - Poll wait is sliced to <=200 ms so tunnel_should_stop() is observed promptly.
 * - Returns early with -1 when tunnel_should_stop() is set.
 *
 * Diagnostics intent:
 * - Tracks skip/decrypt counters and logs them on exit paths to aid field debugging.
 *
 * @return Plain payload length on success; -1 on timeout, cancellation, poll/recv errors, decrypt failure, or
 *         output buffer overflow.
 */
static int recv_plain(int esp_fd, esp_keys_t *esp, esp_keys_t *esp_alt, int *used_alt, uint8_t *out,
                      size_t out_cap, int timeout_ms) {
  struct timeval start;
  recv_plain_stats_t stats = {0};
  if (used_alt != NULL) *used_alt = 0;
  gettimeofday(&start, NULL);

  /* Bounded retry loop to prevent endless keepalive/noise processing. */
  for (int iter = 0; iter < 256; iter++) {
    if (tunnel_should_stop()) {
      tunnel_engine_log(ANDROID_LOG_INFO, LOG_TAG, "l2tp recv_plain: canceled before poll");
      return -1;
    }
    struct timeval now;
    gettimeofday(&now, NULL);
    int elapsed_ms = (int)((now.tv_sec - start.tv_sec) * 1000 + (now.tv_usec - start.tv_usec) / 1000);
    if (elapsed_ms >= timeout_ms) {
      tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG,
                          "l2tp recv_plain: deadline elapsed=%d timeout_ms=%d (no valid ESP payload)", elapsed_ms,
                          timeout_ms);
      recv_plain_log_stats("deadline", &stats, elapsed_ms, timeout_ms);
      return -1;
    }
    int remaining_ms = timeout_ms - elapsed_ms;
    if (remaining_ms <= 0) return -1;

    /* Poll in short slices so cancellation checks stay responsive. */
    struct pollfd pfd = {.fd = esp_fd, .events = POLLIN};
    int waited = 0;
    int pr = 0;
    while (waited < remaining_ms) {
      if (tunnel_should_stop()) {
        tunnel_engine_log(ANDROID_LOG_INFO, LOG_TAG, "l2tp recv_plain: canceled while waiting for control packet");
        return -1;
      }
      int slice_ms = remaining_ms - waited;
      if (slice_ms > 200) slice_ms = 200;
      pr = poll(&pfd, 1, slice_ms);
      waited += slice_ms;
      if (pr != 0) break;
    }
    if (pr < 0) {
      if (errno == EINTR) continue;
      if (tunnel_should_stop()) {
        tunnel_engine_log(ANDROID_LOG_INFO, LOG_TAG, "l2tp recv_plain: canceled after poll interruption");
        return -1;
      }
      tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "l2tp recv_plain: poll errno=%d", errno);
      return -1;
    }
    if (pr == 0) {
      gettimeofday(&now, NULL);
      int elapsed_after = (int)((now.tv_sec - start.tv_sec) * 1000 + (now.tv_usec - start.tv_usec) / 1000);
      tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG,
                          "l2tp recv_plain: poll timeout (elapsed_after=%d ms last_slice=%d ms budget=%d ms)", elapsed_after,
                          remaining_ms, timeout_ms);
      recv_plain_log_stats("poll-timeout", &stats, elapsed_after, timeout_ms);
      esp_log_drop_counters("l2tp recv_plain timeout", 0);
      return -1;
    }

    uint8_t raw[4096];
    ssize_t n = recv(esp_fd, raw, sizeof(raw), 0);
    if (n < 0) {
      if (errno == EINTR) continue;
      tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "l2tp recv_plain: recv errno=%d", errno);
      return -1;
    }
    if (n == 0) {
      tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "l2tp recv_plain: recv EOF");
      return -1;
    }

    l2tp_hs_trace_evt("recv udp4500", raw, n);
    stats.rx_datagrams++;

    if (esp->udp_encap && nat_t_keepalive_is_probe(raw, (size_t)n)) {
      tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "l2tp recv_plain: skip NAT-T keepalive (1 byte 0xff)");
      l2tp_hs_trace_evt("skip natt-keepalive", raw, n);
      stats.skip_keepalive++;
      continue;
    }

    if (n >= 8 && s_recv_plain_info_budget > 0) {
      s_recv_plain_info_budget--;
      uint32_t w0 = util_read_be32(raw);
      uint32_t w1 = n >= 12 ? util_read_be32(raw + 4) : 0u;
      uint32_t w2 = n >= 16 ? util_read_be32(raw + 8) : 0u;
      tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                          "l2tp recv_plain: n=%zd be32[0..8]= %08x %08x %08x (NAT-T/IKE/ESP prefix)", n,
                          (unsigned)w0, (unsigned)w1, (unsigned)w2);
    } else {
      tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "l2tp recv_plain: got %zd bytes raw[0..3]=%02x%02x%02x%02x", n,
                          raw[0], n > 1 ? raw[1] : 0, n > 2 ? raw[2] : 0, n > 3 ? raw[3] : 0);
    }

    if (esp->udp_encap && (size_t)n >= 4 && util_read_be32(raw) == ESP_UDP_NON_ESP_MARKER &&
        isakmp_datagram_like(raw + 4, (size_t)n - 4)) {
      tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "l2tp recv_plain: skip IKE (RFC 3948 non-ESP + ISAKMP)");
      l2tp_hs_trace_evt("skip ike nonesp+isakmp", raw, n);
      stats.skip_ike++;
      continue;
    }
    if (esp->udp_encap && isakmp_datagram_like(raw, (size_t)n)) {
      tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "l2tp recv_plain: skip IKE (ISAKMP on UDP 4500)");
      l2tp_hs_trace_evt("skip ike bare", raw, n);
      stats.skip_ike++;
      continue;
    }

    if (esp->enc_key_len == 0) {
      /* Cleartext mode: accept the datagram as-is when encryption is not configured. */
      if ((size_t)n > out_cap) return -1;
      memcpy(out, raw, (size_t)n);
      recv_plain_log_stats("cleartext", &stats, elapsed_ms, timeout_ms);
      return (int)n;
    }
    size_t plen = out_cap;
    stats.decrypt_try_primary++;
    if (esp_try_decrypt(esp, raw, (size_t)n, out, &plen) == 0) {
      stats.decrypt_ok_primary++;
      if (used_alt != NULL) *used_alt = 0;
      recv_plain_log_stats("decrypt-primary", &stats, elapsed_ms, timeout_ms);
      return (int)plen;
    }
    stats.decrypt_fail_primary++;

    if (esp_alt != NULL) {
      plen = out_cap;
      stats.decrypt_try_alt++;
      /* Secondary keyset supports rekey transitions and alternate SA selection. */
      if (esp_try_decrypt(esp_alt, raw, (size_t)n, out, &plen) == 0) {
        stats.decrypt_ok_alt++;
        if (used_alt != NULL) *used_alt = 1;
        recv_plain_log_stats("decrypt-alt", &stats, elapsed_ms, timeout_ms);
        return (int)plen;
      }
      stats.decrypt_fail_alt++;
    }
    if ((size_t)n >= 36) {
      char detail[160];
      esp_decrypt_last_fail_snprint(detail, sizeof(detail));
      tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "l2tp recv_plain: esp_try_decrypt failed raw_len=%zd %s", n,
                        detail);
      l2tp_hs_trace_evt("decrypt-fail (see esp warn)", raw, n);
    } else {
      tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                        "l2tp recv_plain: esp_try_decrypt skip raw_len=%zd (IKE retransmit or garbage)", n);
      l2tp_hs_trace_evt("decrypt-skip short", raw, n);
    }
    continue;
  }

  tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "l2tp recv_plain: exceeded max iterations (keepalive loop)");
  recv_plain_log_stats("max-iter", &stats, timeout_ms, timeout_ms);
  return -1;
}

static void avp_write(uint8_t *buf, size_t *off, uint16_t attr_type, const void *val, uint16_t vlen) {
  uint16_t tot = (uint16_t)(6 + vlen);
  uint16_t hdr = (uint16_t)(0x8000u | tot);
  util_write_be16(buf + *off + 0, hdr);
  util_write_be16(buf + *off + 2, 0);
  util_write_be16(buf + *off + 4, attr_type);
  memcpy(buf + *off + 6, val, vlen);
  *off += 6u + vlen;
}

static void avp_u16(uint8_t *buf, size_t *off, uint16_t attr_type, uint16_t v) {
  uint8_t tmp[2];
  util_write_be16(tmp, v);
  avp_write(buf, off, attr_type, tmp, 2);
}

static void ingest_peer_ns(l2tp_session_t *s, uint16_t peer_ns) { s->recv_nr_expected = (uint16_t)(peer_ns + 1u); }

static int esp_prepare_profile_variant(const esp_keys_t *src, esp_keys_t *dst, int swap_direction,
                                       int swap_split_order) {
  *dst = *src;
  if (src->enc_key_len != 16 || src->auth_key_len != 20) return -1;
  if (src->spi_i == 0 || src->spi_r == 0) return -1;
  if (swap_split_order) {
    tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG,
                      "l2tp: split-swap fallback disabled (known invalid enc/auth mapping)");
    return -1;
  }

  uint8_t out_mat[36];
  uint8_t in_mat[36];
  memcpy(out_mat + 0, src->enc_key + 0, 16);
  memcpy(out_mat + 16, src->auth_key + 0, 20);
  memcpy(in_mat + 0, src->enc_key + 16, 16);
  memcpy(in_mat + 16, src->auth_key + 20, 20);

  const uint8_t *chosen_out = swap_direction ? in_mat : out_mat;
  const uint8_t *chosen_in = swap_direction ? out_mat : in_mat;

  dst->spi_i = swap_direction ? src->spi_r : src->spi_i;
  dst->spi_r = swap_direction ? src->spi_i : src->spi_r;

  memcpy(dst->enc_key + 0, chosen_out + 0, 16);
  memcpy(dst->auth_key + 0, chosen_out + 16, 20);
  memcpy(dst->enc_key + 16, chosen_in + 0, 16);
  memcpy(dst->auth_key + 20, chosen_in + 16, 20);
  dst->seq_i = 1;
  dst->replay_bitmap = 0;
  dst->replay_top = 0;
  dst->outbound_profile = swap_direction ? 1 : 0;
  return 0;
}

static int send_ctrl(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len, l2tp_session_t *s,
                     const uint8_t *avps, size_t avp_len) {
  uint8_t pkt[2048];
  size_t tot = 0;
  if (l2tp_ctrl_build(pkt, sizeof(pkt), s->tunnel_id, s->session_id, s->send_ns, s->recv_nr_expected, avps, avp_len,
                      &tot) != 0) {
    return -1;
  }
  tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                      "l2tp send_ctrl: tid=%u sid=%u ns=%u nr=%u avp_len=%zu pkt_len=%zu",
                      s->tunnel_id, s->session_id, s->send_ns, s->recv_nr_expected, avp_len, tot);
  char hexbuf[256]; size_t hlen = tot < 32 ? tot : 32;
  for (size_t hi = 0; hi < hlen; hi++) snprintf(hexbuf + hi*3, 4, "%02x ", pkt[hi]);
  tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "l2tp send_ctrl hex: %s", hexbuf);
  if (esp_encrypt_send(esp_fd, esp, peer, peer_len, pkt, tot) < 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "l2tp send_ctrl: esp_encrypt_send failed");
    return -1;
  }
  s->send_ns = (uint16_t)(s->send_ns + 1u);
  return 0;
}

/** Zero-Length Body: 12-byte control header only (RFC 2661 sec 5.8). */
static int send_zlb(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len, l2tp_session_t *s) {
  uint8_t pkt[L2TP_CTRL_HDR];
  size_t pkt_len = 0;
  if (l2tp_ctrl_build(pkt, sizeof(pkt), s->tunnel_id, s->session_id, s->send_ns, s->recv_nr_expected, NULL, 0,
                      &pkt_len) != 0) {
    return -1;
  }
  if (esp_encrypt_send(esp_fd, esp, peer, peer_len, pkt, pkt_len) < 0) return -1;
  return 0;
}

int l2tp_send_teardown(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len,
                       l2tp_session_t *s) {
  if (esp == NULL || peer == NULL || s == NULL || s->tunnel_id == 0) return -1;

  int rc = 0;
  if (s->session_id != 0 && s->peer_session_id != 0) {
    uint8_t avps[64];
    size_t ao = 0;
    l2tp_session_t call = *s;
    call.session_id = s->session_id;
    if (l2tp_avp_append_u16(avps, sizeof(avps), &ao, L2TP_AVP_MSG_TYPE, L2TP_MSG_CDN) != 0 ||
        l2tp_avp_append_result(avps, sizeof(avps), &ao, 3, 0) != 0 ||
        l2tp_avp_append_u16(avps, sizeof(avps), &ao, L2TP_AVP_ASSIGNED_SESSION, s->peer_session_id) != 0 ||
        send_ctrl(esp_fd, esp, peer, peer_len, &call, avps, ao) != 0) {
      rc = -1;
      tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "l2tp teardown: CDN send failed");
    } else {
      s->send_ns = call.send_ns;
      tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "l2tp teardown: CDN sent tid=%u sid=%u",
                        (unsigned)s->tunnel_id, (unsigned)s->session_id);
    }
  }

  {
    uint8_t avps[64];
    size_t ao = 0;
    l2tp_session_t ctrl = *s;
    ctrl.session_id = 0;
    if (l2tp_avp_append_u16(avps, sizeof(avps), &ao, L2TP_AVP_MSG_TYPE, L2TP_MSG_STOPCCN) != 0 ||
        l2tp_avp_append_result(avps, sizeof(avps), &ao, 6, 0) != 0 ||
        l2tp_avp_append_u16(avps, sizeof(avps), &ao, L2TP_AVP_ASSIGNED_TUNNEL, s->peer_tunnel_id) != 0 ||
        send_ctrl(esp_fd, esp, peer, peer_len, &ctrl, avps, ao) != 0) {
      rc = -1;
      tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "l2tp teardown: STOPCCN send failed");
    } else {
      s->send_ns = ctrl.send_ns;
      tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "l2tp teardown: STOPCCN sent tid=%u", (unsigned)s->tunnel_id);
    }
  }

  return rc;
}

static uint16_t pick_local_id(uint16_t remote, uint16_t salt) {
  uint16_t x = (uint16_t)((remote ^ salt ^ 0x5a5du) | 1u);
  if (x == remote) x = (uint16_t)(x + 2u);
  if (x == 0) x = 1;
  return x;
}

static int recv_ctrl_update_nr(int esp_fd, esp_keys_t *esp, esp_keys_t *esp_alt, uint8_t *in, size_t cap,
                               int timeout_ms, l2tp_session_t *s, uint16_t *out_mt, int *used_alt) {
  int ilen = recv_plain(esp_fd, esp, esp_alt, used_alt, in, cap, timeout_ms);
  if (ilen < 0) {
    tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "l2tp recv_ctrl: recv_plain failed/timeout ilen=%d", ilen);
    return -1;
  }
  if (ilen < (int)L2TP_CTRL_HDR) {
    tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "l2tp recv_ctrl: short decrypted packet ilen=%d", ilen);
    return -1;
  }
  uint16_t total = util_read_be16(in + 2);
  if (total != (uint16_t)ilen) {
    tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG,
                        "l2tp recv_ctrl: length mismatch total=%u ilen=%d flags=0x%04x",
                        (unsigned)total, ilen, (unsigned)util_read_be16(in));
    return -1;
  }
  uint16_t peer_ns, peer_nr;
  if (l2tp_ctrl_get_ns_nr(in, (size_t)ilen, &peer_ns, &peer_nr) != 0) return -1;
  (void)peer_nr;
  ingest_peer_ns(s, peer_ns);
  if (l2tp_ctrl_result_ok(in, (size_t)ilen) != 0) {
    uint16_t mt_dbg = 0;
    uint16_t result_code = 0;
    uint16_t error_code = 0;
    char error_msg[128];
    error_msg[0] = '\0';
    int details = l2tp_ctrl_result_details(in, (size_t)ilen, &result_code, &error_code, error_msg, sizeof(error_msg));
    (void)l2tp_ctrl_msg_type(in, (size_t)ilen, &mt_dbg);
    tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG,
                      "l2tp ctrl result-not-ok: mt=%u ilen=%d flags=0x%04x peer_ns=%u peer_nr=%u result=%u error=%u details=%d msg=%s",
                      (unsigned)mt_dbg, ilen, (unsigned)util_read_be16(in), (unsigned)peer_ns, (unsigned)peer_nr,
                      (unsigned)result_code, (unsigned)error_code, details, error_msg[0] != '\0' ? error_msg : "-");
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "l2tp: Result Code AVP error");
    return -1;
  }
  if (out_mt) {
    if (l2tp_ctrl_msg_type(in, (size_t)ilen, out_mt) != 0) {
      // ZLB/ACK control packets may have no Message Type AVP.
      *out_mt = 0;
      tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                        "l2tp ctrl recv: no Message Type AVP (treat as ZLB/ack) ilen=%d flags=0x%04x peer_ns=%u peer_nr=%u local_nr_now=%u",
                        ilen, (unsigned)util_read_be16(in), (unsigned)peer_ns, (unsigned)peer_nr,
                        (unsigned)s->recv_nr_expected);
    } else {
      uint16_t tid_dbg = util_read_be16(in + 4);
      uint16_t sid_dbg = util_read_be16(in + 6);
      tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                        "l2tp ctrl recv: mt=%u ilen=%d flags=0x%04x peer_tid=%u peer_sid=%u peer_ns=%u peer_nr=%u local_nr_now=%u",
                        (unsigned)*out_mt, ilen, (unsigned)util_read_be16(in), (unsigned)tid_dbg, (unsigned)sid_dbg,
                        (unsigned)peer_ns, (unsigned)peer_nr, (unsigned)s->recv_nr_expected);
    }
  }
  return ilen;
}

static int recv_until_mt(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len, uint8_t *in,
                         size_t cap, l2tp_session_t *s, uint16_t want_mt) {
  for (int i = 0; i < 12; i++) {
    uint16_t mt = 0;
    int ilen = recv_ctrl_update_nr(esp_fd, esp, NULL, in, cap, 8000, s, &mt, NULL);
    if (ilen < 0) return -1;
    if (mt == want_mt) return ilen;
    if (mt == 0) {
      // ZLB or control ack with no Message Type AVP.
      tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                        "l2tp ctrl recv: zlb/ack while waiting want=%u iter=%d send_ns=%u recv_nr=%u",
                        (unsigned)want_mt, i, (unsigned)s->send_ns, (unsigned)s->recv_nr_expected);
      continue;
    }
    if (mt == 5) {
      if (send_zlb(esp_fd, esp, peer, peer_len, s) != 0) return -1;
      continue;
    }
    tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG,
                      "l2tp ctrl unexpected mt=%u want=%u iter=%d send_ns=%u recv_nr=%u",
                      (unsigned)mt, (unsigned)want_mt, i, (unsigned)s->send_ns, (unsigned)s->recv_nr_expected);
    tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "l2tp: unexpected msg type %u (want %u)", (unsigned)mt,
                         (unsigned)want_mt);
  }
  return -1;
}

/**
 * Run L2TPv2 control-plane handshake (SCCRQ through ICCN) over the ESP/cleartext UDP path.
 *
 * Phases:
 * - Send SCCRQ with mandatory AVPs; wait for SCCRP and learn peer tunnel id.
 * - If SCCRP times out, retry with alternate ESP key-direction profile (interop with some gateways).
 * - Send SCCCN, then ICRQ/ICRP/ICCN to establish session ids.
 *
 * Side effects: initializes @p s tunnel/session ids; may mutate @p esp when fallback profile succeeds.
 *
 * @return 0 when ICCN is sent and session is ready; -1 on timeout, parse, or send failure.
 */
static int l2tp_handshake_inner(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len,
                                l2tp_session_t *s) {
  memset(s, 0, sizeof(*s));
  s_recv_plain_info_budget = 64;
  esp_reset_drop_counters();

  uint16_t local_tid = 0x1001;
  s->peer_tunnel_id = local_tid;

  uint8_t avps[512];
  size_t ao = 0;

  // RFC 2661 sec 5.2.1 SCCRQ: AVP order and mandatory set (xl2tpd rejects SCCRQ without Firmware Revision).
  avp_u16(avps, &ao, L2TP_AVP_MSG_TYPE, L2TP_MSG_SCCRQ);
  avp_u16(avps, &ao, 2, 0x0100); /* Protocol Version 1.0 */
  { uint8_t fc[4] = {0, 0, 0, 3}; avp_write(avps, &ao, 3, fc, 4); } /* sync + async framing */
  { uint8_t bc[4] = {0, 0, 0, 3}; avp_write(avps, &ao, 4, bc, 4); } /* digital + analog bearer */
  avp_u16(avps, &ao, 6, 1); /* Firmware Revision (required) */
  {
    const char host[] = "tunnel_forge";
    avp_write(avps, &ao, 7, host, (uint16_t)strlen(host));
  }
  avp_u16(avps, &ao, L2TP_AVP_ASSIGNED_TUNNEL, local_tid);
  avp_u16(avps, &ao, 10, 1024); /* Receive Window Size (optional; aids interop) */
  if (send_ctrl(esp_fd, esp, peer, peer_len, s, avps, ao) != 0) return -1;

  uint8_t in[4096];
  uint16_t mt = 0;
  /* First SCCRP can be slow on loaded xl2tpd / lossy Wi-Fi; runtime saw rx=0 @ 8s with no decrypt attempts. */
  int ilen = recv_ctrl_update_nr(esp_fd, esp, NULL, in, sizeof(in), 15000, s, &mt, NULL);
  if (ilen < 0) {
    struct profile_try {
      int swap_direction;
      int swap_split_order;
      const char *name;
    };
    static const struct profile_try tries[] = {
        {1, 0, "alt-direction"},
    };
    /* SCCRP missing: some peers only decrypt when SPI/key direction matches; try prepared variant. */
    tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG,
                      "l2tp: SCCRP wait failed after primary profile; trying ESP profile fallbacks");
    for (size_t ti = 0; ti < sizeof(tries) / sizeof(tries[0]) && ilen < 0; ti++) {
      esp_keys_t esp_alt;
      if (esp_prepare_profile_variant(esp, &esp_alt, tries[ti].swap_direction, tries[ti].swap_split_order) != 0) {
        tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "l2tp: fallback profile unavailable (%s)", tries[ti].name);
        continue;
      }
      if (send_ctrl(esp_fd, &esp_alt, peer, peer_len, s, avps, ao) != 0) {
        tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "l2tp: alternate SCCRQ send failed (%s)", tries[ti].name);
        continue;
      }
      int used_alt = 0;
      ilen = recv_ctrl_update_nr(esp_fd, esp, &esp_alt, in, sizeof(in), 15000, s, &mt, &used_alt);
      if (ilen >= 0) {
        if (used_alt) {
          *esp = esp_alt;
          tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                            "l2tp: SCCRP received with fallback profile=%s spi_out=%08x spi_in=%08x",
                            tries[ti].name, (unsigned)esp->spi_i, (unsigned)esp->spi_r);
        } else {
          tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                            "l2tp: SCCRP received while fallback active, using primary profile spi_out=%08x spi_in=%08x",
                            (unsigned)esp->spi_i, (unsigned)esp->spi_r);
        }
      } else {
        tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "l2tp: SCCRP not received with fallback profile=%s",
                          tries[ti].name);
      }
    }
  }
  if (ilen < 0) return -1;
  if (mt != L2TP_MSG_SCCRP) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "l2tp: expected SCCRP got %u", (unsigned)mt);
    return -1;
  }
  uint16_t remote_tid = 0;
  if (l2tp_avp_first_u16(in, (size_t)ilen, L2TP_AVP_ASSIGNED_TUNNEL, &remote_tid) != 0 || remote_tid == 0) return -1;

  s->tunnel_id = remote_tid;
  if (local_tid == remote_tid) local_tid = (uint16_t)(remote_tid + 1u);
  s->peer_tunnel_id = local_tid;

  ao = 0;
  avp_u16(avps, &ao, L2TP_AVP_MSG_TYPE, L2TP_MSG_SCCCN);
  avp_u16(avps, &ao, L2TP_AVP_ASSIGNED_TUNNEL, local_tid);
  if (send_ctrl(esp_fd, esp, peer, peer_len, s, avps, ao) != 0) return -1;

  // RFC 2661: ICRQ must carry caller-assigned Session ID (AVP 14).
  uint16_t local_sid = pick_local_id(local_tid, 0x2222u);
  if (local_sid == 0) local_sid = 1;

  ao = 0;
  avp_u16(avps, &ao, L2TP_AVP_MSG_TYPE, L2TP_MSG_ICRQ);
  avp_u16(avps, &ao, L2TP_AVP_ASSIGNED_SESSION, local_sid);
  avp_u16(avps, &ao, 15, 1);
  tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                    "l2tp icrq avps: msg_type=%u assigned_session=%u call_serial=%u call_serial_width=%u",
                    (unsigned)L2TP_MSG_ICRQ, (unsigned)local_sid, 1u, 16u);
  if (send_ctrl(esp_fd, esp, peer, peer_len, s, avps, ao) != 0) return -1;

  ilen = recv_until_mt(esp_fd, esp, peer, peer_len, in, sizeof(in), s, L2TP_MSG_ICRP);
  if (ilen < 0) return -1;
  uint16_t remote_sid = 0;
  if (l2tp_avp_first_u16(in, (size_t)ilen, L2TP_AVP_ASSIGNED_SESSION, &remote_sid) != 0 || remote_sid == 0) return -1;

  s->session_id = remote_sid;
  s->peer_session_id = local_sid;

  ao = 0;
  avp_u16(avps, &ao, L2TP_AVP_MSG_TYPE, L2TP_MSG_ICCN);
  avp_u16(avps, &ao, L2TP_AVP_ASSIGNED_SESSION, local_sid);
  if (send_ctrl(esp_fd, esp, peer, peer_len, s, avps, ao) != 0) return -1;

  /* Drain stray post-handshake datagram so data-plane loop does not inherit stale control noise. */
  (void)recv_plain(esp_fd, esp, NULL, NULL, in, sizeof(in), 1500);

  tunnel_log("l2tp ok remote_tid=%u remote_sid=%u local_tid=%u local_sid=%u", (unsigned)s->tunnel_id,
             (unsigned)s->session_id, (unsigned)s->peer_tunnel_id, (unsigned)s->peer_session_id);
  return 0;
}

/** Public entry: enables handshake trace budget then delegates to l2tp_handshake_inner(). */
int l2tp_handshake(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len, l2tp_session_t *s) {
  s_l2tp_hs_trace_rem = 128;
  int r = l2tp_handshake_inner(esp_fd, esp, peer, peer_len, s);
  s_l2tp_hs_trace_rem = 0;
  return r;
}

// RFC 2661 sec 3.6 (data message): T/L/S/O and optional fields before PPP payload.
#define L2TP_DATA_FLAG_T 0x8000u
#define L2TP_DATA_FLAG_L 0x4000u
#define L2TP_DATA_FLAG_S 0x0800u
#define L2TP_DATA_FLAG_O 0x0200u

/** Rate-limited WARN for L2TP data PPP extract failures (dataplane diagnosis). */
static void l2tp_warn_data_extract(const char *why, uint16_t flags, size_t plain_len, const l2tp_session_t *s,
                                   uint16_t tid, uint16_t sid, int have_tid_sid) {
  static time_t last;
  time_t now = time(NULL);
  if (now - last < 3) return;
  last = now;
  if (have_tid_sid && s != NULL) {
    tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG,
                      "l2tp_data extract: %s len=%zu flags=0x%04x want_tid=%u want_sid=%u got_tid=%u got_sid=%u", why,
                      plain_len, (unsigned)flags, (unsigned)s->peer_tunnel_id, (unsigned)s->peer_session_id,
                      (unsigned)tid, (unsigned)sid);
  } else {
    tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "l2tp_data extract: %s len=%zu flags=0x%04x", why, plain_len,
                      (unsigned)flags);
  }
}

/**
 * Parse RFC 2661 L2TP Data Message header and return pointer/length of embedded PPP payload.
 *
 * Walks flags T/L/S/O optional fields, validates tunnel/session ids against @p s when non-NULL.
 *
 * @param diag When non-zero, rate-limited WARN on validation failures.
 * @return 0 with outputs set on success; -1 on malformed header or tid/sid mismatch.
 */
int l2tp_data_extract_ppp(const uint8_t *plain, size_t plain_len, const l2tp_session_t *s, const uint8_t **ppp_out,
                          size_t *ppp_len_out, int diag) {
  if (plain_len < 2u || ppp_out == NULL || ppp_len_out == NULL) {
    if (diag) l2tp_warn_data_extract("bad_args_or_short", 0, plain_len, s, 0, 0, 0);
    return -1;
  }
  uint16_t flags = util_read_be16(plain + 0);
  if ((flags & L2TP_DATA_FLAG_T) != 0u) {
    if (diag) l2tp_warn_data_extract("type_T_not_data", flags, plain_len, s, 0, 0, 0);
    return -1;
  }
  /* Data message: optional L (length), required tunnel/session, optional S (Ns/Nr), optional O (offset). */
  size_t off = 2u;
  if ((flags & L2TP_DATA_FLAG_L) != 0u) {
    if (plain_len < off + 2u) {
      if (diag) l2tp_warn_data_extract("trunc_after_L", flags, plain_len, s, 0, 0, 0);
      return -1;
    }
    off += 2u;
  }
  if (plain_len < off + 4u) {
    if (diag) l2tp_warn_data_extract("trunc_tid_sid", flags, plain_len, s, 0, 0, 0);
    return -1;
  }
  uint16_t tid = util_read_be16(plain + off);
  uint16_t sid = util_read_be16(plain + off + 2u);
  off += 4u;
  if ((flags & L2TP_DATA_FLAG_S) != 0u) {
    if (plain_len < off + 4u) {
      if (diag) l2tp_warn_data_extract("trunc_after_S", flags, plain_len, s, tid, sid, 1);
      return -1;
    }
    off += 4u;
  }
  if ((flags & L2TP_DATA_FLAG_O) != 0u) {
    if (plain_len < off + 2u) {
      if (diag) l2tp_warn_data_extract("trunc_O_hdr", flags, plain_len, s, tid, sid, 1);
      return -1;
    }
    uint16_t osize = util_read_be16(plain + off);
    off += 2u;
    if (plain_len < off + (size_t)osize) {
      if (diag) l2tp_warn_data_extract("trunc_O_payload", flags, plain_len, s, tid, sid, 1);
      return -1;
    }
    off += (size_t)osize;
  }
  if (s != NULL && (tid != s->peer_tunnel_id || sid != s->peer_session_id)) {
    if (diag) l2tp_warn_data_extract("tid_sid_mismatch", flags, plain_len, s, tid, sid, 1);
    return -1;
  }
  if (plain_len < off) {
    if (diag) l2tp_warn_data_extract("len_before_payload", flags, plain_len, s, tid, sid, 1);
    return -1;
  }
  *ppp_out = plain + off;
  *ppp_len_out = plain_len - off;
  return 0;
}

int l2tp_send_ppp(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len, l2tp_session_t *s,
                  const uint8_t *ppp, size_t ppp_len) {
  uint8_t pkt[4096];
  // RFC 2661 sec 3.6: L=1 length field (8-byte header). Observed: compact (L=0) + aligned LCP could still
  // be followed by peer control-only traffic; long header matches common xl2tpd transmit shape.
  size_t tot = 8u + ppp_len;
  if (tot > sizeof(pkt)) return -1;
  util_write_be16(pkt + 0, 0x4002);
  util_write_be16(pkt + 2, (uint16_t)tot);
  util_write_be16(pkt + 4, s->tunnel_id);
  util_write_be16(pkt + 6, s->session_id);
  memcpy(pkt + 8, ppp, ppp_len);
  return esp_encrypt_send(esp_fd, esp, peer, peer_len, pkt, tot);
}

static void l2tp_log_ctrl_result_details(uint16_t mt, const uint8_t *data, size_t len) {
  uint16_t result_code = 0;
  uint16_t error_code = 0;
  char error_msg[128];
  error_msg[0] = '\0';
  int details = l2tp_ctrl_result_details(data, len, &result_code, &error_code, error_msg, sizeof(error_msg));
  if (details != 0) return;
  tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                    "l2tp ctrl result: mt=%u result=%u error=%u msg=%s", (unsigned)mt, (unsigned)result_code,
                    (unsigned)error_code, error_msg[0] != '\0' ? error_msg : "-");
}

static int l2tp_dispatch_control(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len,
                                 l2tp_session_t *s, const uint8_t *data, size_t len) {
  if (s != NULL) {
    if (len < L2TP_CTRL_HDR) return L2TP_DISPATCH_ERROR;
    uint16_t tid = util_read_be16(data + 4);
    uint16_t sid = util_read_be16(data + 6);
    if (tid != 0 && tid != s->peer_tunnel_id) return L2TP_DISPATCH_OK;
    if (sid != 0 && sid != s->peer_session_id) return L2TP_DISPATCH_OK;
  }

  uint16_t mt = 0;
  if (l2tp_ctrl_msg_type(data, len, &mt) != 0) {
    return L2TP_DISPATCH_OK;
  }

  uint16_t peer_ns = 0;
  uint16_t peer_nr = 0;
  if (l2tp_ctrl_get_ns_nr(data, len, &peer_ns, &peer_nr) == 0 && s != NULL) {
    ingest_peer_ns(s, peer_ns);
  }

  if (esp != NULL && peer != NULL && s != NULL) {
    if (send_zlb(esp_fd, esp, peer, peer_len, s) != 0) {
      tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG,
                        "l2tp ctrl dataplane: ZLB ack failed mt=%u peer_ns=%u peer_nr=%u", (unsigned)mt,
                        (unsigned)peer_ns, (unsigned)peer_nr);
      return L2TP_DISPATCH_ERROR;
    }
  }

  if (mt == L2TP_MSG_HELLO) {
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                      "l2tp ctrl dataplane: HELLO acked peer_ns=%u peer_nr=%u", (unsigned)peer_ns,
                      (unsigned)peer_nr);
    return L2TP_DISPATCH_OK;
  }

  if (mt == L2TP_MSG_STOPCCN || mt == L2TP_MSG_CDN) {
    l2tp_log_ctrl_result_details(mt, data, len);
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                      "l2tp ctrl dataplane: remote teardown mt=%u peer_ns=%u peer_nr=%u", (unsigned)mt,
                      (unsigned)peer_ns, (unsigned)peer_nr);
    return L2TP_DISPATCH_REMOTE_CLOSED;
  }

  tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                    "l2tp ctrl dataplane: acked mt=%u peer_ns=%u peer_nr=%u", (unsigned)mt, (unsigned)peer_ns,
                    (unsigned)peer_nr);
  return L2TP_DISPATCH_OK;
}

int l2tp_dispatch_incoming(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len,
                           l2tp_session_t *s, const uint8_t *data, size_t len, packet_endpoint_t *endpoint,
                           ppp_session_t *ppp) {
  if (len < 2) return -1;
  uint16_t flags = util_read_be16(data + 0);
  if ((flags & 0x8000u) != 0) {
    return l2tp_dispatch_control(esp_fd, esp, peer, peer_len, s, data, len);
  }
  const uint8_t *ppp_ptr = NULL;
  size_t ppp_len = 0;
  if (l2tp_data_extract_ppp(data, len, s, &ppp_ptr, &ppp_len, 1) != 0) return 0;
  return ppp_dispatch_ppp_frame(esp_fd, esp, peer, peer_len, s, ppp, ppp_ptr, ppp_len, endpoint);
}
