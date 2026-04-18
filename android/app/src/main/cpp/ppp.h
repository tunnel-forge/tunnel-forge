#ifndef TUNNEL_FORGE_PPP_H
#define TUNNEL_FORGE_PPP_H

#include "esp_udp.h"
#include "l2tp.h"
#include "packet_endpoint.h"

#include <stddef.h>
#include <stdint.h>
#include <sys/socket.h>

/**
 * ppp_negotiate: LCP + auth + IPCP on the L2TP session (recv/tx over esp_fd).
 * ppp_encapsulate_and_send: TUN IP packet -> PPP/L2TP/ESP toward peer.
 * ppp_dispatch_ppp_frame: inbound PPP frame -> IPv4 to TUN; IPv6CP -> LCP Protocol-Reject; other logged.
 */
int ppp_negotiate(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len, l2tp_session_t *l2tp,
                  const char *user, const char *password, ppp_session_t *ppp);

int ppp_encapsulate_and_send(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len,
                             l2tp_session_t *l2tp, ppp_session_t *ppp, const uint8_t *ip_packet, size_t len);

int ppp_dispatch_ppp_frame(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len,
                           l2tp_session_t *l2tp, ppp_session_t *ppp, const uint8_t *frame, size_t len,
                           packet_endpoint_t *endpoint);

#endif
