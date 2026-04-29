#ifndef TUNNEL_FORGE_ESP_UDP_H
#define TUNNEL_FORGE_ESP_UDP_H

/*
 * ESP transport-mode keys and helpers: AES-CBC + HMAC-SHA1-96 over UDP (RFC 3948) or cleartext.
 * enc_key/auth_key layout: first enc_key_len / auth_key_len bytes are encrypt/sign material when
 * split keys are used; see esp_udp.c.
 */

#include <stddef.h>
#include <stdint.h>
#include <sys/socket.h>

typedef struct {
  uint8_t enc_key[32];
  uint8_t auth_key[64];
  size_t enc_key_len;
  size_t auth_key_len;
  uint32_t spi_i;
  uint32_t spi_r;
  uint32_t seq_i;
  /** RFC4303-style sliding window (32 seqs); replay_top is highest seen. */
  uint32_t replay_bitmap;
  uint32_t replay_top;
  /**
   * Outbound SPI/key mapping: 0 = as negotiated (spi_i encrypt), 1 = swapped fallback when the
   * peer's Quick Mode direction does not match our first assumption.
   */
  uint8_t outbound_profile;
  /** Nonzero: ESP in UDP (RFC 3948), e.g. port 4500 with optional non-ESP marker prefix. */
  int udp_encap;
  /** Our tunnel IPv4 in network byte order for the inner IP header on encrypt. */
  uint8_t ip_src[4];
  /** Peer tunnel IPv4 for the inner IP header on encrypt. */
  uint8_t ip_dst[4];
} esp_keys_t;

/**
 * Decrypt/validate inbound ESP (or pass-through when enc_key_len == 0).
 * @param out_len in: capacity of @p out; out: plaintext length on success.
 */
int esp_try_decrypt(esp_keys_t *k, const uint8_t *in, size_t in_len, uint8_t *out, size_t *out_len);

/** After esp_try_decrypt returns -1, format the last failure reason for logs (single-threaded tunnel use). */
void esp_decrypt_last_fail_snprint(char *buf, size_t buflen);

/** Build ESP packet from plaintext L2TP payload and sendto @p peer. @return sendto byte count or -1. */
int esp_encrypt_send(int fd, esp_keys_t *k, const struct sockaddr *peer, socklen_t peer_len, const uint8_t *plain,
                     size_t plain_len);

void esp_reset_drop_counters(void);

void esp_log_drop_counters(const char *ctx, int reset_after_log);

/** True if datagram is single-byte 0xff NAT-T keepalive (not ESP). */
int esp_is_nat_keepalive_probe(const uint8_t *in, size_t in_len);

#endif
