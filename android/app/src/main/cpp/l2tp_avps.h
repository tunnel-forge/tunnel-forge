#ifndef TUNNEL_FORGE_L2TP_AVPS_H
#define TUNNEL_FORGE_L2TP_AVPS_H

#include <stddef.h>
#include <stdint.h>

#define L2TP_AVP_MSG_TYPE 0
#define L2TP_AVP_RESULT_CODE 1
#define L2TP_AVP_ASSIGNED_TUNNEL 9
#define L2TP_AVP_ASSIGNED_SESSION 14

#define L2TP_MSG_SCCRQ 1
#define L2TP_MSG_SCCRP 2
#define L2TP_MSG_SCCCN 3
#define L2TP_MSG_STOPCCN 4
#define L2TP_MSG_HELLO 6
#define L2TP_MSG_ICRQ 10
#define L2TP_MSG_ICRP 11
#define L2TP_MSG_ICCN 12
#define L2TP_MSG_CDN 14

/** Minimum L2TP control header size (flags/ver, len, tid, sid, ns, nr). */
#define L2TP_CTRL_HDR 12

/** Find first AVP of [attr_type] with value length >= 2; read first u16 of value into [out]. */
int l2tp_avp_first_u16(const uint8_t *pkt, size_t len, uint16_t attr_type, uint16_t *out);

/** Read Result Code AVP (type 1): returns 0 if absent or result code is 0; -1 on error/nonzero. */
int l2tp_ctrl_result_ok(const uint8_t *pkt, size_t len);

/**
 * Read Result Code AVP (type 1) details.
 * Returns:
 *   0 when AVP is present and parsed,
 *   1 when AVP is absent,
 *  -1 on malformed AVP.
 */
int l2tp_ctrl_result_details(const uint8_t *pkt, size_t len, uint16_t *result_code, uint16_t *error_code,
                             char *error_msg, size_t error_msg_cap);

/** Message Type AVP (type 0) value. */
int l2tp_ctrl_msg_type(const uint8_t *pkt, size_t len, uint16_t *mt);

int l2tp_ctrl_get_ns_nr(const uint8_t *pkt, size_t len, uint16_t *ns, uint16_t *nr);

#endif
