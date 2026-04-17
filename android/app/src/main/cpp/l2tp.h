#ifndef TUNNEL_FORGE_L2TP_H
#define TUNNEL_FORGE_L2TP_H

#include "esp_udp.h"

#include <stddef.h>
#include <stdint.h>
#include <sys/socket.h>

typedef struct {
  /** Remote tunnel/session IDs (place in outbound L2TP headers toward peer). */
  uint16_t tunnel_id;
  uint16_t session_id;
  /** Local IDs we assigned (peer places these in headers toward us). */
  uint16_t peer_tunnel_id;
  uint16_t peer_session_id;
  /** Control-plane sequence: next Ns to send; Nr to send (= last peer Ns + 1 mod 2^16). */
  uint16_t send_ns;
  uint16_t recv_nr_expected;
} l2tp_session_t;

typedef struct {
  /** Our IPv4 from IPCP (Configure-Ack / NAK-assigned); use for TUN addAddress when available. */
  uint8_t local_ip[4];
  /** Peer IPv4 from their IPCP Configure-Request option 3 (link remote), if present. */
  uint8_t peer_ip[4];
  uint8_t dns[4];
  int have_ip;
  /** LCP option 8 (ACFC) accepted via peer Configure-Request: if set, omit FF 03 on IP data frames. */
  int lcp_acfc;
} ppp_session_t;

int l2tp_handshake(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len, l2tp_session_t *s);

/**
 * RFC 2661 L2TPv2 data channel: extract PPP payload (handles L/S/O bits; L=0 short header).
 * If diag != 0, rate-limited WARN on failure (dataplane); if 0, silent (e.g. recv_ppp already logs).
 */
int l2tp_data_extract_ppp(const uint8_t *plain, size_t plain_len, const l2tp_session_t *s, const uint8_t **ppp_out,
                          size_t *ppp_len_out, int diag);

int l2tp_dispatch_incoming(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len,
                           l2tp_session_t *s, const uint8_t *data, size_t len, int tun_fd, ppp_session_t *ppp);

int l2tp_send_ppp(int esp_fd, esp_keys_t *esp, const struct sockaddr *peer, socklen_t peer_len, l2tp_session_t *s,
                  const uint8_t *ppp, size_t ppp_len);

#endif
