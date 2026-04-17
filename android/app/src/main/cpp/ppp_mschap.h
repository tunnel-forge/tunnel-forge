#ifndef TUNNEL_FORGE_PPP_MSCHAP_H
#define TUNNEL_FORGE_PPP_MSCHAP_H

#include <stddef.h>
#include <stdint.h>

/** RFC 2759 GenerateNTResponse + CHAPv2 value blob (49 bytes): PeerChal16 + Resv8 + NTResp24 + Flags1 */
int ppp_mschapv2_response_value(const char *username, const char *password, const uint8_t auth_challenge[16],
 uint8_t peer_challenge[16], uint8_t value49_out[49]);

#endif
