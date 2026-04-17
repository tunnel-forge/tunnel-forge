/*
 * MS-CHAPv2 crypto primitives (RFC 2759): NT hash, challenge/response, 49-byte CHAP Value field.
 * Public entry point is declared in ppp_mschap.h.
 */
#include "ppp_mschap.h"

#include <mbedtls/des.h>
#include <mbedtls/md4.h>
#include <mbedtls/sha1.h>
#include <string.h>

static void mschap_expand_des_key(const uint8_t key7[7], uint8_t key8[8]) {
  uint8_t next = 0;
  for (int i = 0; i < 7; i++) {
    uint8_t tmp = key7[i];
    key8[i] = (uint8_t)((tmp >> i) | next | 1);
    next = (uint8_t)(tmp << (7 - i));
  }
  key8[7] = (uint8_t)(next | 1);
}

static int mschap_des_encrypt_block(const uint8_t clear[8], const uint8_t key7[7], uint8_t cypher[8]) {
  uint8_t key8[8];
  mschap_expand_des_key(key7, key8);
  mbedtls_des_context ctx;
  mbedtls_des_init(&ctx);
  if (mbedtls_des_setkey_enc(&ctx, key8) != 0) {
    mbedtls_des_free(&ctx);
    return -1;
  }
  if (mbedtls_des_crypt_ecb(&ctx, clear, cypher) != 0) {
    mbedtls_des_free(&ctx);
    return -1;
  }
  mbedtls_des_free(&ctx);
  return 0;
}

static int challenge_response(const uint8_t challenge[8], const uint8_t password_hash[16], uint8_t response[24]) {
  uint8_t z[21];
  memset(z, 0, sizeof(z));
  memcpy(z, password_hash, 16);
  if (mschap_des_encrypt_block(challenge, z + 0, response + 0) != 0) return -1;
  if (mschap_des_encrypt_block(challenge, z + 7, response + 8) != 0) return -1;
  if (mschap_des_encrypt_block(challenge, z + 14, response + 16) != 0) return -1;
  return 0;
}

static void password_to_unicode_le(const char *pass, uint8_t *out, size_t *out_len, size_t out_cap) {
  size_t n = 0;
  for (const unsigned char *p = (const unsigned char *)pass; *p && n + 2 <= out_cap; p++) {
    out[n++] = *p;
    out[n++] = 0;
  }
  *out_len = n;
}

static int nt_password_hash(const char *password, uint8_t hash[16]) {
  uint8_t unicode[512];
  size_t ulen = 0;
  password_to_unicode_le(password, unicode, &ulen, sizeof(unicode));
  if (ulen == 0) return -1;
  mbedtls_md4_context ctx;
  mbedtls_md4_init(&ctx);
  if (mbedtls_md4_starts_ret(&ctx) != 0) {
    mbedtls_md4_free(&ctx);
    return -1;
  }
  if (mbedtls_md4_update_ret(&ctx, unicode, ulen) != 0) {
    mbedtls_md4_free(&ctx);
    return -1;
  }
  if (mbedtls_md4_finish_ret(&ctx, hash) != 0) {
    mbedtls_md4_free(&ctx);
    return -1;
  }
  mbedtls_md4_free(&ctx);
  return 0;
}

static int challenge_hash(const uint8_t peer_chal[16], const uint8_t auth_chal[16], const char *username,
                          uint8_t chal8[8]) {
  const char *user = username;
  const char *bs = strrchr(username, '\\');
  if (bs != NULL) user = bs + 1;

  mbedtls_sha1_context sha;
  mbedtls_sha1_init(&sha);
  if (mbedtls_sha1_starts_ret(&sha) != 0) goto fail;
  if (mbedtls_sha1_update_ret(&sha, peer_chal, 16) != 0) goto fail;
  if (mbedtls_sha1_update_ret(&sha, auth_chal, 16) != 0) goto fail;
  if (mbedtls_sha1_update_ret(&sha, (const uint8_t *)user, strlen(user)) != 0) goto fail;
  uint8_t d[20];
  if (mbedtls_sha1_finish_ret(&sha, d) != 0) goto fail;
  mbedtls_sha1_free(&sha);
  memcpy(chal8, d, 8);
  return 0;
fail:
  mbedtls_sha1_free(&sha);
  return -1;
}

static int generate_nt_response(const uint8_t auth_chal[16], const uint8_t peer_chal[16], const char *username,
                                const char *password, uint8_t nt_resp[24]) {
  uint8_t chal8[8];
  uint8_t phash[16];
  if (challenge_hash(peer_chal, auth_chal, username, chal8) != 0) return -1;
  if (nt_password_hash(password, phash) != 0) return -1;
  return challenge_response(chal8, phash, nt_resp);
}

int ppp_mschapv2_response_value(const char *username, const char *password, const uint8_t auth_challenge[16],
                                uint8_t peer_challenge[16], uint8_t value49_out[49]) {
  uint8_t nt[24];
  if (generate_nt_response(auth_challenge, peer_challenge, username, password, nt) != 0) return -1;
  memcpy(value49_out, peer_challenge, 16);
  memset(value49_out + 16, 0, 8);
  memcpy(value49_out + 24, nt, 24);
  value49_out[48] = 0;
  return 0;
}
