/*
 * ESP in UDP-encapsulated form (RFC 3948): decrypt with AES-CBC + HMAC-SHA1-96, replay window,
 * optional non-ESP marker; encrypt/send builds outer UDP/IP as needed for tunnel mode toward peer.
 */
#include "esp_udp.h"

#include "engine.h"
#include "nat_t_keepalive.h"

#include <android/log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <mbedtls/cipher.h>
#include <mbedtls/ctr_drbg.h>
#include <mbedtls/entropy.h>
#include <mbedtls/md.h>
#include <netinet/in.h>
#include <stdio.h>
#include <string.h>

#define LOG_TAG "tunnel_engine"

typedef enum {
  ESP_FAIL_NONE = 0,
  ESP_FAIL_NAT_KEEPALIVE,
  ESP_FAIL_SHORT_UDP_PREFIX,
  ESP_FAIL_SHORT_ESP,
  ESP_FAIL_SHORT_POST_IV,
  ESP_FAIL_SPI,
  ESP_FAIL_REPLAY_SEQ0,
  ESP_FAIL_REPLAY_OLD,
  ESP_FAIL_REPLAY_DUP,
  ESP_FAIL_HMAC,
  ESP_FAIL_AES,
  ESP_FAIL_PLAIN_SHORT,
  ESP_FAIL_NEXT_HEADER,
  ESP_FAIL_PAD_LEN,
  ESP_FAIL_PAD_BYTE,
  ESP_FAIL_UDP_LEN,
  ESP_FAIL_L2TP_CAP,
  ESP_FAIL_PAYLOAD_SHORT,
  ESP_FAIL_MD_SETUP,
  ESP_FAIL_CIPHER_SETUP,
  ESP_FAIL_CIPHER_SETKEY,
  ESP_FAIL_CIPHER_PADMODE,
  ESP_FAIL_OUT_CAP,
  ESP_FAIL_CT_OVERSIZE,
} esp_fail_kind_t;

static struct {
  esp_fail_kind_t kind;
  size_t in_len;
  uint32_t a;
  uint32_t b;
  size_t sa;
  size_t sb;
  uint8_t nh;
  uint8_t head[8];
} s_last_fail;

static void esp_fail_capture(esp_fail_kind_t kind, size_t in_len, const uint8_t *head_src, size_t head_avail, uint32_t a,
                             uint32_t b, size_t sa, size_t sb, uint8_t nh) {
  s_last_fail.kind = kind;
  s_last_fail.in_len = in_len;
  s_last_fail.a = a;
  s_last_fail.b = b;
  s_last_fail.sa = sa;
  s_last_fail.sb = sb;
  s_last_fail.nh = nh;
  memset(s_last_fail.head, 0, sizeof(s_last_fail.head));
  if (head_src && head_avail) {
    size_t n = head_avail < sizeof(s_last_fail.head) ? head_avail : sizeof(s_last_fail.head);
    memcpy(s_last_fail.head, head_src, n);
  }
}

void esp_decrypt_last_fail_snprint(char *buf, size_t buflen) {
  if (buf == NULL || buflen == 0) return;
  char hex[32];
  hex[0] = '\0';
  if (s_last_fail.kind != ESP_FAIL_NONE) {
    static const char *xd = "0123456789abcdef";
    size_t pos = 0;
    for (int i = 0; i < 8 && pos + 2 < sizeof(hex); i++) {
      hex[pos++] = xd[(s_last_fail.head[i] >> 4) & 0xf];
      hex[pos++] = xd[s_last_fail.head[i] & 0xf];
    }
    hex[pos] = '\0';
  }
  switch (s_last_fail.kind) {
  case ESP_FAIL_NONE:
    snprintf(buf, buflen, "unknown(no_detail)");
    break;
  case ESP_FAIL_NAT_KEEPALIVE:
    snprintf(buf, buflen, "nat_keepalive_1byte_ff");
    break;
  case ESP_FAIL_SHORT_UDP_PREFIX:
    snprintf(buf, buflen, "short_udp4500_prefix left=%zu in_len=%zu head=%s", s_last_fail.sa, s_last_fail.in_len, hex);
    break;
  case ESP_FAIL_SHORT_ESP:
    snprintf(buf, buflen, "short_esp_body left=%zu in_len=%zu need>=36 head=%s", s_last_fail.sa, s_last_fail.in_len,
             hex);
    break;
  case ESP_FAIL_SHORT_POST_IV:
    snprintf(buf, buflen, "short_after_iv left=%zu icv=12 in_len=%zu head=%s", s_last_fail.sa, s_last_fail.in_len, hex);
    break;
  case ESP_FAIL_SPI:
    snprintf(buf, buflen, "spi got=%08x want=%08x head=%s", (unsigned)s_last_fail.a, (unsigned)s_last_fail.b, hex);
    break;
  case ESP_FAIL_REPLAY_SEQ0:
    snprintf(buf, buflen, "replay seq=0 head=%s", hex);
    break;
  case ESP_FAIL_REPLAY_OLD:
    snprintf(buf, buflen, "replay_old seq=%08x top=%08x", (unsigned)s_last_fail.a, (unsigned)s_last_fail.b);
    break;
  case ESP_FAIL_REPLAY_DUP:
    snprintf(buf, buflen, "replay_dup seq=%08x top=%08x", (unsigned)s_last_fail.a, (unsigned)s_last_fail.b);
    break;
  case ESP_FAIL_HMAC:
    snprintf(buf, buflen, "hmac_mismatch head=%s", hex);
    break;
  case ESP_FAIL_AES:
    snprintf(buf, buflen, "aes_cbc_failed head=%s", hex);
    break;
  case ESP_FAIL_PLAIN_SHORT:
    snprintf(buf, buflen, "plain_too_short olen=%zu head=%s", s_last_fail.sa, hex);
    break;
  case ESP_FAIL_NEXT_HEADER:
    snprintf(buf, buflen, "next_header=%u want=17 head=%s", (unsigned)s_last_fail.nh, hex);
    break;
  case ESP_FAIL_PAD_LEN:
    snprintf(buf, buflen, "bad_pad_len pad=%u olen=%zu", (unsigned)s_last_fail.nh, s_last_fail.sa);
    break;
  case ESP_FAIL_PAD_BYTE:
    snprintf(buf, buflen, "bad_pad_byte idx=%zu", s_last_fail.sa);
    break;
  case ESP_FAIL_UDP_LEN:
    snprintf(buf, buflen, "bad_inner_udp_len udp_len=%u payload=%zu", (unsigned)s_last_fail.a, s_last_fail.sa);
    break;
  case ESP_FAIL_L2TP_CAP:
    snprintf(buf, buflen, "l2tp_len=%zu out_cap=%zu", s_last_fail.sa, s_last_fail.sb);
    break;
  case ESP_FAIL_PAYLOAD_SHORT:
    snprintf(buf, buflen, "inner_payload=%zu need>=8", s_last_fail.sa);
    break;
  case ESP_FAIL_MD_SETUP:
    snprintf(buf, buflen, "mbedtls_md_setup");
    break;
  case ESP_FAIL_CIPHER_SETUP:
    snprintf(buf, buflen, "mbedtls_cipher_setup");
    break;
  case ESP_FAIL_CIPHER_SETKEY:
    snprintf(buf, buflen, "mbedtls_cipher_setkey");
    break;
  case ESP_FAIL_CIPHER_PADMODE:
    snprintf(buf, buflen, "mbedtls_cipher_set_padding_mode");
    break;
  case ESP_FAIL_OUT_CAP:
    snprintf(buf, buflen, "out_buf_too_small ct_len=%zu cap=%zu", s_last_fail.sa, s_last_fail.sb);
    break;
  case ESP_FAIL_CT_OVERSIZE:
    snprintf(buf, buflen, "ct_len=%zu max_ct=%zu", s_last_fail.sa, s_last_fail.sb);
    break;
  default:
    snprintf(buf, buflen, "kind=%d", (int)s_last_fail.kind);
    break;
  }
}

static mbedtls_entropy_context s_esp_entropy;
static mbedtls_ctr_drbg_context s_esp_drbg;
static int s_esp_drbg_inited;
static int s_esp_logged_inner_ports;
static unsigned s_esp_send_profile_logs;
static unsigned s_esp_keymap_profile_once;

typedef struct {
  uint64_t spi_mismatch;
  uint64_t replay_seq_zero;
  uint64_t replay_old;
  uint64_t replay_dup;
  uint64_t hmac_mismatch;
  uint64_t aes_decrypt_failed;
  uint64_t plain_too_short;
  uint64_t next_header_bad;
  uint64_t pad_len_bad;
  uint64_t pad_byte_bad;
  uint64_t udp_len_bad;
  uint64_t l2tp_len_bad;
} esp_drop_stats_t;

static esp_drop_stats_t s_esp_drop_stats;

void esp_reset_drop_counters(void) { memset(&s_esp_drop_stats, 0, sizeof(s_esp_drop_stats)); }

void esp_log_drop_counters(const char *ctx, int reset_after_log) {
  tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                    "esp drop counters [%s]: spi=%llu seq0=%llu replay_old=%llu replay_dup=%llu hmac=%llu "
                    "aes=%llu short=%llu nh=%llu pad_len=%llu pad_byte=%llu udp_len=%llu l2tp_len=%llu",
                    ctx ? ctx : "n/a", (unsigned long long)s_esp_drop_stats.spi_mismatch,
                    (unsigned long long)s_esp_drop_stats.replay_seq_zero,
                    (unsigned long long)s_esp_drop_stats.replay_old,
                    (unsigned long long)s_esp_drop_stats.replay_dup,
                    (unsigned long long)s_esp_drop_stats.hmac_mismatch,
                    (unsigned long long)s_esp_drop_stats.aes_decrypt_failed,
                    (unsigned long long)s_esp_drop_stats.plain_too_short,
                    (unsigned long long)s_esp_drop_stats.next_header_bad,
                    (unsigned long long)s_esp_drop_stats.pad_len_bad,
                    (unsigned long long)s_esp_drop_stats.pad_byte_bad,
                    (unsigned long long)s_esp_drop_stats.udp_len_bad,
                    (unsigned long long)s_esp_drop_stats.l2tp_len_bad);
  if (reset_after_log) esp_reset_drop_counters();
}

static void esp_ensure_drbg(void) {
  if (s_esp_drbg_inited) return;
  mbedtls_entropy_init(&s_esp_entropy);
  mbedtls_ctr_drbg_init(&s_esp_drbg);
  const char *p = "tunnel_forge_esp";
  if (mbedtls_ctr_drbg_seed(&s_esp_drbg, mbedtls_entropy_func, &s_esp_entropy, (const uint8_t *)p, strlen(p)) != 0) {
    mbedtls_ctr_drbg_free(&s_esp_drbg);
    mbedtls_entropy_free(&s_esp_entropy);
    return;
  }
  s_esp_drbg_inited = 1;
}

/**
 * Validate and decrypt one inbound ESP datagram into its inner L2TP payload.
 *
 * The input may be either a raw ESP packet or an RFC 3948 UDP/4500 packet with the optional
 * four-byte non-ESP marker. Accepted encrypted packets must pass, in order:
 * 1. NAT-T keepalive filtering and optional non-ESP marker stripping.
 * 2. ESP header minimum-length and responder SPI validation.
 * 3. Replay-window validation using a pending state update.
 * 4. HMAC-SHA1-96 verification over ESP header, IV, and ciphertext.
 * 5. AES-CBC decrypt with mbedTLS padding disabled.
 * 6. ESP trailer, incremental padding, next-header, inner UDP length, and L2TP capacity checks.
 *
 * Replay state is intentionally committed only after every authentication, decrypt, and inner-payload
 * validation step succeeds; malformed or unauthenticated packets must not advance the replay window.
 *
 * @param k       Active inbound ESP keys and replay-window state. When enc_key_len is 0, this is a cleartext pass-through.
 * @param in      Received datagram bytes from the shared IKE/ESP UDP socket.
 * @param in_len  Number of bytes available at @p in.
 * @param out     Caller-owned output buffer. On success it contains only the L2TP payload, without the inner UDP header.
 * @param out_len In: capacity of @p out. Out: number of L2TP payload bytes written.
 *
 * @return 0 on successful pass-through/decrypt, -1 on framing, SPI, replay, authentication, decrypt, padding, or capacity failure.
 */
int esp_try_decrypt(esp_keys_t *k, const uint8_t *in, size_t in_len, uint8_t *out, size_t *out_len) {
  if (k->enc_key_len == 0) {
    if (in_len > *out_len) return -1;
    memcpy(out, in, in_len);
    *out_len = in_len;
    s_last_fail.kind = ESP_FAIL_NONE;
    return 0;
  }

  /**
   * Step 1: normalize the inbound datagram to the ESP header.
   *
   * NAT-T keepalive probes are valid UDP/4500 traffic but are not ESP. When udp_encap is active,
   * RFC 3948 packets can also carry a zero non-ESP marker before the SPI; authentication later starts
   * after that marker so the ICV input matches the peer's ESP bytes.
   */
  if (k->udp_encap && nat_t_keepalive_is_probe(in, in_len)) {
    esp_fail_capture(ESP_FAIL_NAT_KEEPALIVE, in_len, in, in_len, 0, 0, 0, 0, 0);
    return -1;
  }
  const uint8_t *p = in;
  size_t left = in_len;
  if (k->udp_encap) {
    /* UDP/4500 packets may carry the RFC 3948 non-ESP marker before SPI. */
    if (left < 4) {
      esp_fail_capture(ESP_FAIL_SHORT_UDP_PREFIX, in_len, p, left, 0, 0, left, 0, 0);
      return -1;
    }
    uint32_t m = util_read_be32(p);
    if (m == 0) {
      p += 4;
      left -= 4;
    }
  }
  const uint8_t *esp_head = p;
  const size_t esp_need = 8 + 16 + 12;
  if (left < esp_need) {
    esp_fail_capture(ESP_FAIL_SHORT_ESP, in_len, esp_head, left < 8 ? left : 8, 0, 0, left, 0, 0);
    return -1;
  }
  uint32_t spi = util_read_be32(p);
  p += 4;
  left -= 4;
  if (spi != k->spi_r) {
    s_esp_drop_stats.spi_mismatch++;
    esp_fail_capture(ESP_FAIL_SPI, in_len, esp_head, 8, spi, k->spi_r, 0, 0, 0);
    tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "esp decrypt spi mismatch got=%08x want=%08x",
                      (unsigned)spi, (unsigned)k->spi_r);
    return -1;
  }

  /**
   * Step 2: check replay protection using pending next-state values.
   *
   * The packet sequence number must be accepted before expensive crypto work, but replay_top and
   * replay_bitmap are not written back until HMAC, decrypt, padding, and inner UDP validation all pass.
   */
  uint32_t seq = util_read_be32(p);
  uint32_t replay_top_next = k->replay_top;
  uint32_t replay_bitmap_next = k->replay_bitmap;
  int replay_commit = 0;
  if (k->enc_key_len != 0) {
    /* Replay window check follows RFC-style sliding bitmap semantics (32-packet window). */
    const uint32_t W = 32;
    if (seq == 0) {
      s_esp_drop_stats.replay_seq_zero++;
      esp_fail_capture(ESP_FAIL_REPLAY_SEQ0, in_len, esp_head, 8, 0, 0, 0, 0, 0);
      tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "esp decrypt drop: seq=0");
      return -1;
    }
    replay_commit = 1;
    if (replay_top_next == 0) {
      replay_top_next = seq;
      replay_bitmap_next = 1u;
    } else if (seq > replay_top_next) {
      uint32_t d = seq - replay_top_next;
      if (d >= W) {
        replay_bitmap_next = 1u;
        replay_top_next = seq;
      } else {
        replay_bitmap_next = (replay_bitmap_next << d) | 1u;
        replay_top_next = seq;
      }
    } else {
      uint32_t d = replay_top_next - seq;
      if (d >= W) {
        s_esp_drop_stats.replay_old++;
        esp_fail_capture(ESP_FAIL_REPLAY_OLD, in_len, esp_head, 8, seq, replay_top_next, 0, 0, 0);
        tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "esp decrypt drop: replay seq too old seq=%08x top=%08x",
                          (unsigned)seq, (unsigned)replay_top_next);
        return -1;
      }
      if (replay_bitmap_next & (1u << d)) {
        s_esp_drop_stats.replay_dup++;
        esp_fail_capture(ESP_FAIL_REPLAY_DUP, in_len, esp_head, 8, seq, replay_top_next, 0, 0, 0);
        tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "esp decrypt drop: replay duplicate seq=%08x", (unsigned)seq);
        return -1;
      }
      replay_bitmap_next |= (1u << d);
    }
  }
  p += 4;
  left -= 4;
  const uint8_t *iv = p;
  p += 16;
  left -= 16;
  const size_t icv_len = 12;
  size_t ct_len = left - icv_len;
  const uint8_t *ct = p;
  const uint8_t *icv = p + ct_len;

  /**
   * Step 3: verify integrity with the inbound HMAC-SHA1-96 key.
   *
   * The ICV covers the ESP header through ciphertext and excludes the optional UDP/4500 non-ESP marker.
   * AES decrypt must not run on packets that fail this check.
   */
  uint8_t mac_calc[32];
  const mbedtls_md_info_t *md = mbedtls_md_info_from_type(MBEDTLS_MD_SHA1);
  mbedtls_md_context_t ctx;
  mbedtls_md_init(&ctx);
  if (mbedtls_md_setup(&ctx, md, 1) != 0) {
    mbedtls_md_free(&ctx);
    esp_fail_capture(ESP_FAIL_MD_SETUP, in_len, esp_head, 8, 0, 0, 0, 0, 0);
    return -1;
  }
  const uint8_t *auth_start = in;
  if (k->udp_encap && in_len >= 4u && util_read_be32(in) == 0u) {
    auth_start = in + 4;
  }
  const uint8_t *auth_in = k->auth_key_len ? k->auth_key + k->auth_key_len : k->auth_key;
  mbedtls_md_hmac_starts(&ctx, auth_in, k->auth_key_len);
  mbedtls_md_hmac_update(&ctx, auth_start, (size_t)(icv - auth_start));
  mbedtls_md_hmac_finish(&ctx, mac_calc);
  mbedtls_md_free(&ctx);
  if (memcmp(mac_calc, icv, icv_len) != 0) {
    s_esp_drop_stats.hmac_mismatch++;
    esp_fail_capture(ESP_FAIL_HMAC, in_len, esp_head, 8, 0, 0, 0, 0, 0);
    tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "esp decrypt drop: hmac mismatch (wrong key or corrupt packet)");
    return -1;
  }

  /**
   * Step 4: decrypt the ciphertext with AES-CBC.
   *
   * mbedTLS padding is disabled because ESP has its own trailer layout:
   * payload || 1,2,3... padding || pad length || next header.
   */
  mbedtls_cipher_context_t ciph;
  mbedtls_cipher_init(&ciph);
  const mbedtls_cipher_info_t *info = mbedtls_cipher_info_from_values(MBEDTLS_CIPHER_ID_AES, (int)k->enc_key_len * 8,
                                                                      MBEDTLS_MODE_CBC);
  if (info == NULL) {
    mbedtls_cipher_free(&ciph);
    esp_fail_capture(ESP_FAIL_CIPHER_SETUP, in_len, esp_head, 8, 0, 0, 0, 0, 0);
    return -1;
  }
  const uint8_t *enc_in = k->enc_key_len ? k->enc_key + k->enc_key_len : k->enc_key;
  if (mbedtls_cipher_setup(&ciph, info) != 0) {
    mbedtls_cipher_free(&ciph);
    esp_fail_capture(ESP_FAIL_CIPHER_SETUP, in_len, esp_head, 8, 0, 0, 0, 0, 0);
    return -1;
  }
  if (mbedtls_cipher_setkey(&ciph, enc_in, (int)k->enc_key_len * 8, MBEDTLS_DECRYPT) != 0) {
    mbedtls_cipher_free(&ciph);
    esp_fail_capture(ESP_FAIL_CIPHER_SETKEY, in_len, esp_head, 8, 0, 0, 0, 0, 0);
    return -1;
  }
  if (mbedtls_cipher_set_padding_mode(&ciph, MBEDTLS_PADDING_NONE) != 0) {
    mbedtls_cipher_free(&ciph);
    esp_fail_capture(ESP_FAIL_CIPHER_PADMODE, in_len, esp_head, 8, 0, 0, 0, 0, 0);
    return -1;
  }
  if (ct_len > *out_len) {
    mbedtls_cipher_free(&ciph);
    esp_fail_capture(ESP_FAIL_OUT_CAP, in_len, esp_head, 8, 0, 0, ct_len, *out_len, 0);
    return -1;
  }
  uint8_t tmp[4096];
  if (ct_len > sizeof(tmp)) {
    mbedtls_cipher_free(&ciph);
    esp_fail_capture(ESP_FAIL_CT_OVERSIZE, in_len, esp_head, 8, 0, 0, ct_len, 0, 0);
    return -1;
  }
  memcpy(tmp, ct, ct_len);
  size_t olen = 0;
  uint8_t ivbuf[16];
  memcpy(ivbuf, iv, 16);
  if (mbedtls_cipher_crypt(&ciph, ivbuf, 16, tmp, ct_len, out, &olen) != 0) {
    mbedtls_cipher_free(&ciph);
    s_esp_drop_stats.aes_decrypt_failed++;
    esp_fail_capture(ESP_FAIL_AES, in_len, esp_head, 8, 0, 0, 0, 0, 0);
    tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "esp decrypt drop: aes-cbc decrypt failed");
    return -1;
  }
  mbedtls_cipher_free(&ciph);

  /**
   * Step 5: validate the ESP trailer and unwrap transport-mode UDP.
   *
   * This app expects UDP as ESP next-header and L2TP as the inner UDP payload. Only after the UDP
   * length and caller capacity are validated do we memmove the L2TP payload to the start of @p out.
   */
  if (olen < 2) {
    s_esp_drop_stats.plain_too_short++;
    esp_fail_capture(ESP_FAIL_PLAIN_SHORT, in_len, esp_head, 8, 0, 0, olen, 0, 0);
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "esp decrypt drop: plaintext too short olen=%zu", olen);
    return -1;
  }
  uint8_t next_header = out[olen - 1];
  uint8_t pad_len = out[olen - 2];
  if (next_header != 17) {
    s_esp_drop_stats.next_header_bad++;
    esp_fail_capture(ESP_FAIL_NEXT_HEADER, in_len, esp_head, 8, 0, 0, 0, 0, next_header);
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "esp decrypt drop: next_header=%u want=17", (unsigned)next_header);
    return -1; /* UDP payload in transport mode */
  }
  if ((size_t)pad_len + 2 > olen) {
    s_esp_drop_stats.pad_len_bad++;
    esp_fail_capture(ESP_FAIL_PAD_LEN, in_len, esp_head, 8, 0, 0, olen, 0, pad_len);
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "esp decrypt drop: bad pad_len=%u olen=%zu", (unsigned)pad_len, olen);
    return -1;
  }

  size_t payload_len = olen - (size_t)pad_len - 2;
  if (payload_len < 8) {
    esp_fail_capture(ESP_FAIL_PAYLOAD_SHORT, in_len, esp_head, 8, 0, 0, payload_len, 0, 0);
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "esp decrypt drop: payload too short=%zu", payload_len);
    return -1; /* inner UDP header */
  }
  for (size_t i = 0; i < (size_t)pad_len; i++) {
    if (out[payload_len + i] != (uint8_t)(i + 1)) {
      s_esp_drop_stats.pad_byte_bad++;
      esp_fail_capture(ESP_FAIL_PAD_BYTE, in_len, esp_head, 8, 0, 0, i, 0, 0);
      tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "esp decrypt drop: bad pad byte idx=%zu", i);
      return -1;
    }
  }

  uint16_t udp_len = util_read_be16(out + 4);
  if (udp_len < 8 || udp_len > payload_len) {
    s_esp_drop_stats.udp_len_bad++;
    esp_fail_capture(ESP_FAIL_UDP_LEN, in_len, esp_head, 8, udp_len, 0, payload_len, 0, 0);
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "esp decrypt drop: udp_len=%u payload_len=%zu", (unsigned)udp_len,
                      payload_len);
    return -1;
  }
  size_t l2tp_len = (size_t)udp_len - 8;
  if (l2tp_len > *out_len) {
    s_esp_drop_stats.l2tp_len_bad++;
    esp_fail_capture(ESP_FAIL_L2TP_CAP, in_len, esp_head, 8, 0, 0, l2tp_len, *out_len, 0);
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "esp decrypt drop: l2tp_len=%zu out_cap=%zu", l2tp_len, *out_len);
    return -1;
  }
  memmove(out, out + 8, l2tp_len);
  *out_len = l2tp_len;
  if (replay_commit) {
    k->replay_top = replay_top_next;
    k->replay_bitmap = replay_bitmap_next;
  }
  s_last_fail.kind = ESP_FAIL_NONE;
  return 0;
}

int esp_is_nat_keepalive_probe(const uint8_t *in, size_t in_len) { return nat_t_keepalive_is_probe(in, in_len); }

/* Build inner UDP header used by transport-mode ESP payload.
 * Use src=dst=1701 for L2TP control/data inside ESP; outer UDP/4500 still uses the IKE socket
 * ephemeral (NAT-T). Mirroring the NAT-T port here breaks xl2tpd/kernel decaps paths that expect
 * classic L2TP-in-UDP (RFC 2661). */
static void build_inner_udp(uint8_t *hdr, uint16_t src_port, uint16_t dst_port, uint16_t payload_len) {
  uint16_t udp_len = (uint16_t)(8 + payload_len);
  util_write_be16(hdr + 0, src_port);
  util_write_be16(hdr + 2, dst_port);
  util_write_be16(hdr + 4, udp_len);
  util_write_be16(hdr + 6, 0);
}

/* RFC 768 / RFC 1071 IPv4 UDP checksum over UDP header (checksum field zero) + payload. */
static uint16_t ipv4_udp_checksum(const uint8_t ip_src[4], const uint8_t ip_dst[4], const uint8_t *udp,
 size_t udp_len) {
  uint32_t sum = 0;
  sum += ((uint32_t)ip_src[0] << 8) | ip_src[1];
  sum += ((uint32_t)ip_src[2] << 8) | ip_src[3];
  sum += ((uint32_t)ip_dst[0] << 8) | ip_dst[1];
  sum += ((uint32_t)ip_dst[2] << 8) | ip_dst[3];
  sum += (uint32_t)IPPROTO_UDP;
  sum += (uint32_t)udp_len;
  for (size_t i = 0; i < udp_len; i += 2) {
    uint32_t w = (uint32_t)udp[i] << 8;
    if (i + 1 < udp_len) w |= udp[i + 1];
    sum += w;
  }
  while (sum >> 16) sum = (sum & 0xffffu) + (sum >> 16);
  uint16_t csum = (uint16_t)~sum;
  if (csum == 0) csum = 0xffffu;
  return csum;
}

/**
 * Encrypt and send one plaintext L2TP payload as ESP transport-mode UDP datagram.
 *
 * Behavior:
 * - Wraps plaintext as inner UDP(L2TP) payload, then builds ESP header/IV/trailer.
 * - Applies AES-CBC encryption and appends 96-bit truncated HMAC-SHA1 ICV.
 * - Sends the final packet to connected peer endpoint and emits bounded diagnostics.
 *
 * @return sendto() byte count on success path, -1 on local build/encrypt/auth failures.
 */
int esp_encrypt_send(int fd, esp_keys_t *k, const struct sockaddr *peer, socklen_t peer_len, const uint8_t *plain,
                     size_t plain_len) {
  if (k->enc_key_len == 0) {
    return (int)sendto(fd, plain, plain_len, 0, peer, peer_len);
  }

  /* Transport mode: wrap plaintext as UDP(src=encap socket, dst=1701) + L2TP payload. */
  uint8_t inner_buf[4096];
  if (plain_len > UINT16_MAX - 8) return -1;
  if (plain_len + 8 > sizeof(inner_buf)) return -1;
  memcpy(inner_buf + 8, plain, plain_len);
  build_inner_udp(inner_buf, L2TP_PORT, L2TP_PORT, (uint16_t)plain_len);
  /* CMake TUNNEL_FORGE_ESP_INNER_UDP_NO_CHECKSUM (default ON): inner csum 0 often improves server interop. */
#if defined(TUNNEL_FORGE_ESP_INNER_UDP_NO_CHECKSUM) && TUNNEL_FORGE_ESP_INNER_UDP_NO_CHECKSUM
  /* RFC 768: IPv4 UDP checksum optional; 0 = transmitter did not generate checksum. */
  util_write_be16(inner_buf + 6, 0);
#else
  {
    size_t ulen = 8 + plain_len;
    uint16_t csum = ipv4_udp_checksum(k->ip_src, k->ip_dst, inner_buf, ulen);
    util_write_be16(inner_buf + 6, csum);
  }
#endif
  const uint8_t *esp_plain = inner_buf;
  size_t esp_plain_len = plain_len + 8;
  uint16_t inner_csum_logged = util_read_be16(inner_buf + 6);

  uint8_t buf[4096];
  size_t off = 0;
  /* RFC 3948: outbound ESP-on-UDP packet starts directly at SPI (no non-ESP marker). */
  size_t esp_start = 0;
  util_write_be32(buf + off, k->spi_i);
  off += 4;
  uint32_t seq_i = k->seq_i++;
  util_write_be32(buf + off, seq_i);
  off += 4;
  uint8_t iv[16];
  esp_ensure_drbg();
  if (s_esp_drbg_inited) {
    if (mbedtls_ctr_drbg_random(&s_esp_drbg, iv, sizeof(iv)) != 0) return -1;
  } else {
    for (size_t i = 0; i < 16; i++) iv[i] = (uint8_t)(k->seq_i + (uint32_t)i + esp_plain[0]);
  }
  memcpy(buf + off, iv, 16);
  off += 16;
  /* ESP trailer: PKCS#7-style incremental pad bytes + pad length + next header(UDP). */
  size_t pad_len = (16 - ((esp_plain_len + 2) % 16)) % 16;
  size_t ct_len = esp_plain_len + pad_len + 2;
  if (off + ct_len + 12 > sizeof(buf)) return -1;
  uint8_t tmp[4096];
  if (esp_plain_len > sizeof(tmp)) return -1;
  memcpy(tmp, esp_plain, esp_plain_len);
  for (size_t i = 0; i < pad_len; i++) tmp[esp_plain_len + i] = (uint8_t)(i + 1);
  tmp[esp_plain_len + pad_len] = (uint8_t)pad_len;
  tmp[esp_plain_len + pad_len + 1] = 17; /* Next Header: UDP */

  mbedtls_cipher_context_t ciph;
  mbedtls_cipher_init(&ciph);
  const mbedtls_cipher_info_t *info = mbedtls_cipher_info_from_values(MBEDTLS_CIPHER_ID_AES, (int)k->enc_key_len * 8,
                                                                        MBEDTLS_MODE_CBC);
  if (info == NULL) {
    mbedtls_cipher_free(&ciph);
    return -1;
  }
  if (mbedtls_cipher_setup(&ciph, info) != 0 || mbedtls_cipher_setkey(&ciph, k->enc_key, (int)k->enc_key_len * 8,
                                                                       MBEDTLS_ENCRYPT) != 0) {
    mbedtls_cipher_free(&ciph);
    return -1;
  }
  if (mbedtls_cipher_set_padding_mode(&ciph, MBEDTLS_PADDING_NONE) != 0) {
    mbedtls_cipher_free(&ciph);
    return -1;
  }
  size_t olen = 0;
  uint8_t ivcopy[16];
  memcpy(ivcopy, iv, 16);
  if (mbedtls_cipher_crypt(&ciph, ivcopy, 16, tmp, ct_len, buf + off, &olen) != 0) {
    mbedtls_cipher_free(&ciph);
    return -1;
  }
  mbedtls_cipher_free(&ciph);
  off += olen;

  /* Integrity covers ESP header + IV + ciphertext; 96-bit truncation appended as ICV. */
  const mbedtls_md_info_t *md = mbedtls_md_info_from_type(MBEDTLS_MD_SHA1);
  mbedtls_md_context_t mctx;
  mbedtls_md_init(&mctx);
  if (mbedtls_md_setup(&mctx, md, 1) != 0) {
    mbedtls_md_free(&mctx);
    return -1;
  }
  mbedtls_md_hmac_starts(&mctx, k->auth_key, k->auth_key_len);
  mbedtls_md_hmac_update(&mctx, buf + esp_start, off - esp_start);
  uint8_t mac[20];
  mbedtls_md_hmac_finish(&mctx, mac);
  mbedtls_md_free(&mctx);
  memcpy(buf + off, mac, 12);
  off += 12;
  ssize_t sent = sendto(fd, buf, off, 0, peer, peer_len);
  if (s_esp_keymap_profile_once == 0) {
    s_esp_keymap_profile_once = 1;
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                      "esp key profile one-shot: out_spi=%08x in_spi=%08x out_enc=%02x%02x%02x%02x out_auth=%02x%02x%02x%02x in_enc=%02x%02x%02x%02x in_auth=%02x%02x%02x%02x",
                      (unsigned)k->spi_i, (unsigned)k->spi_r,
                      k->enc_key[0], k->enc_key[1], k->enc_key[2], k->enc_key[3],
                      k->auth_key[0], k->auth_key[1], k->auth_key[2], k->auth_key[3],
                      k->enc_key[16], k->enc_key[17], k->enc_key[18], k->enc_key[19],
                      k->auth_key[20], k->auth_key[21], k->auth_key[22], k->auth_key[23]);
  }
  if (s_esp_send_profile_logs < 8) {
    s_esp_send_profile_logs++;
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG,
                      "esp send profile: spi_i=%08x seq=%u udp_encap=%d enc_len=%zu auth_len=%zu inner_udp_csum=0x%04x "
                      "outer_len=%zu mode=%s",
                      (unsigned)k->spi_i, (unsigned)seq_i, k->udp_encap ? 1 : 0, k->enc_key_len, k->auth_key_len,
                      (unsigned)inner_csum_logged, off, k->outbound_profile ? "alt-direction" : "primary");
  }
  if (!s_esp_logged_inner_ports) {
    s_esp_logged_inner_ports = 1;
    tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "esp send inner udp src=%u dst=%u plain_len=%zu pkt_len=%zu",
                        (unsigned)L2TP_PORT, (unsigned)L2TP_PORT, plain_len, off);
  }
  if (sent < 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG,
                        "esp_encrypt_send: sendto failed errno=%d plain_len=%zu pkt_len=%zu udp_encap=%d",
                        errno, plain_len, off, k->udp_encap);
  }
  return (int)sent;
}
