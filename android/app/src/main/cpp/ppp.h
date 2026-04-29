#ifndef TUNNEL_FORGE_PPP_H
#define TUNNEL_FORGE_PPP_H

#include "esp_udp.h"
#include "l2tp.h"
#include "packet_endpoint.h"

#include <stddef.h>
#include <stdint.h>
#include <sys/socket.h>

/** LCP + auth + IPCP on the L2TP session (recv/tx over esp_fd). */
int ppp_negotiate(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len, l2tp_session_t *l2tp,
                  const char *user, const char *password, int tun_mtu, ppp_session_t *ppp);

/** TUN IPv4 packet -> PPP/L2TP/ESP toward peer (checksum fix + MSS clamp per ppp_session_t). */
int ppp_encapsulate_and_send(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len,
                             l2tp_session_t *l2tp, ppp_session_t *ppp, const uint8_t *ip_packet, size_t len);

/** Inbound PPP from peer: IPv4 to endpoint; IPv6CP/LCP handled per implementation in ppp.c. */
int ppp_dispatch_ppp_frame(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len,
                           l2tp_session_t *l2tp, ppp_session_t *ppp, const uint8_t *frame, size_t len,
                           packet_endpoint_t *endpoint);

#endif
