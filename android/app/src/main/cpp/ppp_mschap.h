#ifndef TUNNEL_FORGE_PPP_MSCHAP_H
#define TUNNEL_FORGE_PPP_MSCHAP_H

#include <stddef.h>
#include <stdint.h>

typedef struct {
  int has_error_code;
  int error_code;
  int has_retry;
  int retry;
  int has_version;
  int version;
  int has_message;
  char message[80];
} ppp_mschapv2_failure_info_t;

/** RFC 2759 GenerateNTResponse + CHAPv2 value blob (49 bytes): PeerChal16 + Resv8 + NTResp24 + Flags1 */
int ppp_mschapv2_response_value(const char *username, const char *password, const uint8_t auth_challenge[16],
 uint8_t peer_challenge[16], uint8_t value49_out[49]);

static inline int ppp_mschapv2_parse_uint_token(const uint8_t *p, size_t len, size_t *idx, int *out) {
  int value = 0;
  int digits = 0;
  while (*idx < len && p[*idx] >= (uint8_t)'0' && p[*idx] <= (uint8_t)'9') {
    value = (value * 10) + (int)(p[*idx] - (uint8_t)'0');
    (*idx)++;
    digits++;
  }
  if (digits == 0) return -1;
  if (out != NULL) *out = value;
  return 0;
}

static inline int ppp_mschapv2_parse_failure(const uint8_t *msg, size_t len,
                                             ppp_mschapv2_failure_info_t *out) {
  if (msg == NULL || out == NULL) return -1;
  out->has_error_code = 0;
  out->error_code = 0;
  out->has_retry = 0;
  out->retry = 0;
  out->has_version = 0;
  out->version = 0;
  out->has_message = 0;
  out->message[0] = '\0';

  size_t i = 0;
  while (i < len) {
    while (i < len && (msg[i] == (uint8_t)' ' || msg[i] == (uint8_t)'\t')) i++;
    if (i + 2 > len) break;
    uint8_t key = msg[i++];
    if (i >= len || msg[i] != (uint8_t)'=') {
      while (i < len && msg[i] != (uint8_t)' ') i++;
      continue;
    }
    i++;
    if (key == (uint8_t)'E') {
      if (ppp_mschapv2_parse_uint_token(msg, len, &i, &out->error_code) == 0) out->has_error_code = 1;
    } else if (key == (uint8_t)'R') {
      if (ppp_mschapv2_parse_uint_token(msg, len, &i, &out->retry) == 0) out->has_retry = 1;
    } else if (key == (uint8_t)'V') {
      if (ppp_mschapv2_parse_uint_token(msg, len, &i, &out->version) == 0) out->has_version = 1;
    } else if (key == (uint8_t)'M') {
      size_t j = 0;
      while (i < len && msg[i] != (uint8_t)'\0' && j + 1u < sizeof(out->message)) {
        uint8_t c = msg[i++];
        out->message[j++] = (c >= 32u && c <= 126u) ? (char)c : '?';
      }
      out->message[j] = '\0';
      out->has_message = j > 0u ? 1 : 0;
      break;
    } else {
      while (i < len && msg[i] != (uint8_t)' ') i++;
    }
  }
  return (out->has_error_code || out->has_retry || out->has_version || out->has_message) ? 0 : -1;
}

#endif
