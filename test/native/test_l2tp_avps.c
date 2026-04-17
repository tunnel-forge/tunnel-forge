#include "../../android/app/src/main/cpp/l2tp_avps.h"
#include "../../android/app/src/main/cpp/util_endian.h"

#include <stdio.h>
#include <string.h>

static int fail(const char *msg) {
  fprintf(stderr, "FAIL: %s\n", msg);
  return 1;
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

  printf("ok\n");
  return 0;
}
