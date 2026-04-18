#include "../../android/app/src/main/cpp/l2tp_avps.h"
#include "../../android/app/src/main/cpp/util_endian.h"

#include <stdio.h>
#include <string.h>

static int fail(const char *msg) {
  fprintf(stderr, "FAIL: %s\n", msg);
  return 1;
}

static void append_u16_avp(uint8_t *pkt, size_t *off, uint16_t attr_type, uint16_t value) {
  util_write_be16(pkt + *off, 0x8000u | (6u + 2u));
  *off += 2u;
  util_write_be16(pkt + *off, 0);
  *off += 2u;
  util_write_be16(pkt + *off, attr_type);
  *off += 2u;
  util_write_be16(pkt + *off, value);
  *off += 2u;
}

int main(void) {
  /* SCCRP-like: hdr + Message Type 2 + Assigned Tunnel 0x0009 = 0x0707 */
  uint8_t pkt[64];
  memset(pkt, 0, sizeof(pkt));
  util_write_be16(pkt + 0, 0xC802);
  util_write_be16(pkt + 2, 24);
  util_write_be16(pkt + 4, 0);
  util_write_be16(pkt + 6, 0);
  util_write_be16(pkt + 8, 0);
  util_write_be16(pkt + 10, 0);
  size_t o = 12;
  util_write_be16(pkt + o, 0x8000u | (6u + 2u));
  o += 2;
  util_write_be16(pkt + o, 0);
  o += 2;
  util_write_be16(pkt + o, L2TP_AVP_MSG_TYPE);
  o += 2;
  util_write_be16(pkt + o, L2TP_MSG_SCCRP);
  o += 2;
  util_write_be16(pkt + o, 0x8000u | (6u + 2u));
  o += 2;
  util_write_be16(pkt + o, 0);
  o += 2;
  util_write_be16(pkt + o, L2TP_AVP_ASSIGNED_TUNNEL);
  o += 2;
  util_write_be16(pkt + o, 0x0707);
  o += 2;
  util_write_be16(pkt + 2, (uint16_t)o);

  uint16_t tid = 0;
  if (l2tp_avp_first_u16(pkt, o, L2TP_AVP_ASSIGNED_TUNNEL, &tid) != 0) return fail("assigned tunnel");
  if (tid != 0x0707) return fail("tid value");

  uint16_t mt = 0;
  if (l2tp_ctrl_msg_type(pkt, o, &mt) != 0) return fail("msg type");
  if (mt != L2TP_MSG_SCCRP) return fail("msg type value");

  if (l2tp_ctrl_result_ok(pkt, o) != 0) return fail("result ok");

  uint16_t ns = 0, nr = 0;
  util_write_be16(pkt + 8, 3);
  util_write_be16(pkt + 10, 9);
  if (l2tp_ctrl_get_ns_nr(pkt, o, &ns, &nr) != 0) return fail("ns nr");
  if (ns != 3 || nr != 9) return fail("ns nr values");

  memset(pkt, 0, sizeof(pkt));
  util_write_be16(pkt + 0, 0xC802);
  util_write_be16(pkt + 4, 0x1001);
  util_write_be16(pkt + 8, 7);
  util_write_be16(pkt + 10, 4);
  o = 12;
  append_u16_avp(pkt, &o, L2TP_AVP_MSG_TYPE, L2TP_MSG_HELLO);
  util_write_be16(pkt + 2, (uint16_t)o);
  mt = 0;
  if (l2tp_ctrl_msg_type(pkt, o, &mt) != 0) return fail("hello msg type");
  if (mt != L2TP_MSG_HELLO) return fail("hello msg type value");

  memset(pkt, 0, sizeof(pkt));
  util_write_be16(pkt + 0, 0xC802);
  util_write_be16(pkt + 4, 0x1001);
  util_write_be16(pkt + 8, 8);
  util_write_be16(pkt + 10, 5);
  o = 12;
  append_u16_avp(pkt, &o, L2TP_AVP_MSG_TYPE, L2TP_MSG_STOPCCN);
  util_write_be16(pkt + o, 0x8000u | (6u + 8u));
  o += 2u;
  util_write_be16(pkt + o, 0);
  o += 2u;
  util_write_be16(pkt + o, L2TP_AVP_RESULT_CODE);
  o += 2u;
  util_write_be16(pkt + o, 3);
  o += 2u;
  util_write_be16(pkt + o, 0);
  o += 2u;
  memcpy(pkt + o, "bye!", 4u);
  o += 4u;
  util_write_be16(pkt + 2, (uint16_t)o);
  uint16_t result_code = 0;
  uint16_t error_code = 0;
  char error_msg[16];
  error_msg[0] = '\0';
  if (l2tp_ctrl_msg_type(pkt, o, &mt) != 0) return fail("stopccn msg type");
  if (mt != L2TP_MSG_STOPCCN) return fail("stopccn msg type value");
  if (l2tp_ctrl_result_details(pkt, o, &result_code, &error_code, error_msg, sizeof(error_msg)) != 0) {
    return fail("stopccn result details");
  }
  if (result_code != 3 || error_code != 0) return fail("stopccn result values");
  if (strcmp(error_msg, "bye!") != 0) return fail("stopccn result message");

  printf("ok\n");
  return 0;
}
