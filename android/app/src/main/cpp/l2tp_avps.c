/*
 * Walk L2TP control-message AVPs (RFC 2661): message type, Result Code, Ns/Nr, and helpers for
 * handshake success/failure parsing.
 */
#include "l2tp_avps.h"

#include "util_endian.h"

#include <string.h>

int l2tp_avp_first_u16(const uint8_t *pkt, size_t len, uint16_t attr_type, uint16_t *out) {
  if (pkt == NULL || out == NULL || len < L2TP_CTRL_HDR) return -1;
  size_t off = L2TP_CTRL_HDR;
  while (off + 6 <= len) {
    uint16_t h = util_read_be16(pkt + off);
    uint16_t alen = (uint16_t)(h & 0x03ffu);
    if (alen < 6 || off + alen > len) return -1;
    uint16_t atype = util_read_be16(pkt + off + 4);
    if (atype == attr_type && alen >= 8) {
      *out = util_read_be16(pkt + off + 6);
      return 0;
    }
    off += alen;
  }
  return -1;
}

int l2tp_ctrl_msg_type(const uint8_t *pkt, size_t len, uint16_t *mt) {
  return l2tp_avp_first_u16(pkt, len, L2TP_AVP_MSG_TYPE, mt);
}

int l2tp_ctrl_get_ns_nr(const uint8_t *pkt, size_t len, uint16_t *ns, uint16_t *nr) {
  if (pkt == NULL || ns == NULL || nr == NULL || len < L2TP_CTRL_HDR) return -1;
  *ns = util_read_be16(pkt + 8);
  *nr = util_read_be16(pkt + 10);
  return 0;
}

int l2tp_ctrl_result_ok(const uint8_t *pkt, size_t len) {
  if (len < L2TP_CTRL_HDR) return -1;
  size_t off = L2TP_CTRL_HDR;
  while (off + 6 <= len) {
    uint16_t h = util_read_be16(pkt + off);
    uint16_t alen = (uint16_t)(h & 0x03ffu);
    if (alen < 6 || off + alen > len) return -1;
    uint16_t atype = util_read_be16(pkt + off + 4);
    if (atype == L2TP_AVP_RESULT_CODE) {
      if (alen < 10) return -1;
      uint16_t result = util_read_be16(pkt + off + 6);
      if (result != 0) return -1;
      return 0;
    }
    off += alen;
  }
  return 0;
}

int l2tp_ctrl_result_details(const uint8_t *pkt, size_t len, uint16_t *result_code, uint16_t *error_code,
                             char *error_msg, size_t error_msg_cap) {
  if (pkt == NULL || len < L2TP_CTRL_HDR) return -1;
  if (result_code != NULL) *result_code = 0;
  if (error_code != NULL) *error_code = 0;
  if (error_msg != NULL && error_msg_cap > 0) error_msg[0] = '\0';

  size_t off = L2TP_CTRL_HDR;
  while (off + 6 <= len) {
    uint16_t h = util_read_be16(pkt + off);
    uint16_t alen = (uint16_t)(h & 0x03ffu);
    if (alen < 6 || off + alen > len) return -1;
    uint16_t atype = util_read_be16(pkt + off + 4);
    if (atype == L2TP_AVP_RESULT_CODE) {
      size_t vlen = (size_t)(alen - 6u);
      if (vlen < 2) return -1;
      if (result_code != NULL) *result_code = util_read_be16(pkt + off + 6);
      if (vlen >= 4 && error_code != NULL) *error_code = util_read_be16(pkt + off + 8);
      if (vlen > 4 && error_msg != NULL && error_msg_cap > 0) {
        size_t msg_len = vlen - 4u;
        size_t copy_len = msg_len < (error_msg_cap - 1u) ? msg_len : (error_msg_cap - 1u);
        memcpy(error_msg, pkt + off + 10, copy_len);
        error_msg[copy_len] = '\0';
      }
      return 0;
    }
    off += alen;
  }
  return 1;
}
