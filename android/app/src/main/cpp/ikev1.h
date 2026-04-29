#ifndef TUNNEL_FORGE_IKEV1_H
#define TUNNEL_FORGE_IKEV1_H

#include "esp_udp.h"

#include <stdint.h>
#include <sys/socket.h>

typedef struct {
  uint8_t icookie[8];
  uint8_t rcookie[8];
  /** Phase-1 PRF outputs (lengths follow negotiated transforms). */
  uint8_t skeyid[32];
  size_t skeyid_len;
  uint8_t skeyid_e[32];
  size_t skeyid_e_len;
  uint8_t skeyid_a[32];
  size_t skeyid_a_len;
  uint8_t skeyid_d[32];
  size_t skeyid_d_len;
  uint8_t iv_phase1[16];
  int nat_t;
  /** Phase 1 uses AES-128-CBC instead of 3DES (same MODP2048 DH). */
  int p1_aes;
  /** Connected UDP socket used for IKE then ESP (same fd after negotiation). */
  int esp_fd;
  struct sockaddr_storage peer;
  socklen_t peer_len;
} ike_session_t;

/**
 * Establishes transport for L2TP/UDP to @p server.
 * If @p psk is empty, skips IKE/IPsec and uses plaintext UDP.
 * Otherwise: IKEv1 Main Mode (PSK) with AES-128-CBC or 3DES phase-1 (MODP2048), Quick Mode ESP-UDP
 * (AES-128-CBC / HMAC-SHA1-96). Without NAT-T, ESP+L2TP use UDP 1701 after IKE completes.
 * Returns 0 on success; fills @p esp with SPI/keys when IPsec is used.
 */
int ikev1_connect(const char *server, const char *psk, ike_session_t *ike, esp_keys_t *esp);

#endif
