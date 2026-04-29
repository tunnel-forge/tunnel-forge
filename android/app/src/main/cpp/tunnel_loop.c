/*
 * Orchestrates IKE+ESP, L2TP, and PPP (tunnel_negotiate), then polls TUN and the ESP socket
 * (tunnel_run_loop). g_state holds the negotiated session between phases; g_stop is set from
 * JNI via tunnel_loop_stop for cooperative shutdown.
 */
#include "engine.h"
#include "packet_endpoint.h"

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#include "esp_udp.h"
#include "ikev1.h"
#include "l2tp.h"
#include "nat_t_keepalive.h"
#include "ppp.h"
#include "util_endian.h"

#define LOG_TAG "tunnel_engine"
#define VPN_DNS_PORT 53u
#define VPN_DNS_IPPROTO_TCP 6u
#define VPN_DNS_IPPROTO_UDP 17u
#define VPN_DNS_TCP_RST_LEN 40u

static atomic_int g_stop;

static struct {
  ike_session_t ike;
  esp_keys_t esp;
  l2tp_session_t l2tp;
  ppp_session_t ppp;
  int ready;
} g_state;

static proxy_packet_queue_ctx_t g_proxy_queue;
static atomic_int g_proxy_bridge_active;
static proxy_packet_queue_ctx_t g_vpn_dns_queue;
static atomic_int g_vpn_dns_bridge_active;
static atomic_int g_vpn_dns_tun_fd = ATOMIC_VAR_INIT(-1);
static uint8_t g_vpn_dns_ipv4[4];
static atomic_int g_vpn_dns_intercept_enabled;

void tunnel_loop_stop(void) { atomic_store(&g_stop, 1); }

int tunnel_should_stop(void) { return atomic_load(&g_stop) ? 1 : 0; }

static int parse_ipv4_literal(const char *ipv4, uint8_t out[4]) {
  if (ipv4 == NULL || out == NULL) return -1;
  unsigned int a, b, c, d;
  char tail;
  if (sscanf(ipv4, "%u.%u.%u.%u%c", &a, &b, &c, &d, &tail) != 4) return -1;
  if (a > 255u || b > 255u || c > 255u || d > 255u) return -1;
  out[0] = (uint8_t)a;
  out[1] = (uint8_t)b;
  out[2] = (uint8_t)c;
  out[3] = (uint8_t)d;
  return 0;
}

static uint16_t read_be16_u8(const uint8_t *p) {
  return (uint16_t)(((uint16_t)p[0] << 8) | (uint16_t)p[1]);
}

static uint32_t vpn_dns_csum_acc_bytes(uint32_t sum, const uint8_t *p, size_t n) {
  for (size_t i = 0; i < n; i += 2) {
    uint32_t w = (uint32_t)p[i] << 8;
    if (i + 1 < n) w |= p[i + 1];
    sum += w;
  }
  return sum;
}

static uint16_t vpn_dns_inet_checksum_fold(uint32_t sum) {
  while (sum >> 16) {
    sum = (sum & 0xffffu) + (sum >> 16);
  }
  return (uint16_t)~sum;
}

typedef enum {
  VPN_DNS_PACKET_NONE = 0,
  VPN_DNS_PACKET_UDP,
  VPN_DNS_PACKET_TCP,
} vpn_dns_packet_kind_t;

static vpn_dns_packet_kind_t vpn_dns_packet_kind(const uint8_t *packet, size_t len) {
  if (!atomic_load(&g_vpn_dns_intercept_enabled) || packet == NULL || len < 28u) return 0;
  const uint8_t version = packet[0] >> 4;
  const size_t ihl = (size_t)(packet[0] & 0x0fu) * 4u;
  if (version != 4u || ihl < 20u || len < ihl + 8u) return 0;
  const uint16_t total_len = read_be16_u8(packet + 2);
  if (total_len < ihl + 8u || total_len > len) return 0;
  if (memcmp(packet + 16, g_vpn_dns_ipv4, 4u) != 0) return 0;
  if (packet[9] == VPN_DNS_IPPROTO_UDP) {
    const uint8_t *udp = packet + ihl;
    const uint16_t udp_len = read_be16_u8(udp + 4);
    if (udp_len < 8u || ihl + (size_t)udp_len > (size_t)total_len) return VPN_DNS_PACKET_NONE;
    return read_be16_u8(udp + 2) == VPN_DNS_PORT ? VPN_DNS_PACKET_UDP : VPN_DNS_PACKET_NONE;
  }
  if (packet[9] == VPN_DNS_IPPROTO_TCP) {
    if ((size_t)total_len < ihl + 20u) return VPN_DNS_PACKET_NONE;
    const uint8_t *tcp = packet + ihl;
    const size_t tcp_len = (size_t)total_len - ihl;
    const size_t tcp_hlen = (size_t)(tcp[12] >> 4) * 4u;
    if (tcp_hlen < 20u || tcp_hlen > tcp_len) return VPN_DNS_PACKET_NONE;
    return read_be16_u8(tcp + 2) == VPN_DNS_PORT ? VPN_DNS_PACKET_TCP : VPN_DNS_PACKET_NONE;
  }
  return VPN_DNS_PACKET_NONE;
}

static int vpn_dns_build_tcp_rst(const uint8_t *packet, size_t len, uint8_t out[VPN_DNS_TCP_RST_LEN]) {
  if (packet == NULL || out == NULL || len < 40u) return -1;
  const size_t ihl = (size_t)(packet[0] & 0x0fu) * 4u;
  const uint16_t total_len = read_be16_u8(packet + 2);
  if ((packet[0] >> 4) != 4u || ihl < 20u || total_len < ihl + 20u || total_len > len) return -1;
  if (packet[9] != VPN_DNS_IPPROTO_TCP || memcmp(packet + 16, g_vpn_dns_ipv4, 4u) != 0) return -1;
  const uint8_t *tcp = packet + ihl;
  const size_t tcp_len = (size_t)total_len - ihl;
  const size_t tcp_hlen = (size_t)(tcp[12] >> 4) * 4u;
  if (tcp_hlen < 20u || tcp_hlen > tcp_len || read_be16_u8(tcp + 2) != VPN_DNS_PORT) return -1;
  const uint8_t flags = tcp[13];
  if ((flags & 0x02u) == 0u) return -1;

  memset(out, 0, VPN_DNS_TCP_RST_LEN);
  out[0] = 0x45u;
  util_write_be16(out + 2, VPN_DNS_TCP_RST_LEN);
  util_write_be16(out + 6, 0x4000u);
  out[8] = 64u;
  out[9] = VPN_DNS_IPPROTO_TCP;
  memcpy(out + 12, packet + 16, 4u);
  memcpy(out + 16, packet + 12, 4u);
  util_write_be16(out + 10, vpn_dns_inet_checksum_fold(vpn_dns_csum_acc_bytes(0, out, 20u)));

  uint8_t *rst_tcp = out + 20u;
  util_write_be16(rst_tcp + 0, VPN_DNS_PORT);
  util_write_be16(rst_tcp + 2, read_be16_u8(tcp + 0));
  util_write_be32(rst_tcp + 4, 0u);
  uint32_t ack = util_read_be32(tcp + 4);
  ack += 1u;
  util_write_be32(rst_tcp + 8, ack);
  rst_tcp[12] = 0x50u;
  rst_tcp[13] = 0x14u;

  uint32_t sum = 0;
  sum = vpn_dns_csum_acc_bytes(sum, out + 12, 4u);
  sum = vpn_dns_csum_acc_bytes(sum, out + 16, 4u);
  sum += VPN_DNS_IPPROTO_TCP;
  sum += 20u;
  sum = vpn_dns_csum_acc_bytes(sum, rst_tcp, 20u);
  util_write_be16(rst_tcp + 16, vpn_dns_inet_checksum_fold(sum));
  return 0;
}

static int vpn_dns_intercept_query(const uint8_t *packet, size_t len) {
  vpn_dns_packet_kind_t kind = vpn_dns_packet_kind(packet, len);
  if (kind == VPN_DNS_PACKET_NONE) return 0;
  if (!atomic_load(&g_vpn_dns_bridge_active)) {
    tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "vpn dns query dropped: bridge inactive");
    return 1;
  }
  if (kind == VPN_DNS_PACKET_UDP) {
    if (packet_endpoint_proxy_enqueue_inbound(&g_vpn_dns_queue, packet, len) != 0) {
      tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "vpn dns query dropped: enqueue errno=%d", errno);
    }
    return 1;
  }
  uint8_t rst[VPN_DNS_TCP_RST_LEN];
  if (vpn_dns_build_tcp_rst(packet, len, rst) == 0 &&
      packet_endpoint_proxy_enqueue_outbound(&g_vpn_dns_queue, rst, sizeof(rst)) != 0) {
    tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "vpn dns tcp reset dropped: enqueue errno=%d", errno);
  }
  return 1;
}

static void vpn_dns_try_write_pending_response(packet_endpoint_t *endpoint, uint8_t *pending, size_t *pending_len) {
  if (endpoint == NULL || pending == NULL || pending_len == NULL || *pending_len == 0u) return;
  if (packet_endpoint_write(endpoint, pending, *pending_len) == 0) {
    *pending_len = 0u;
    return;
  }
  if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) return;
  tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "vpn dns response dropped: tun write errno=%d", errno);
  *pending_len = 0u;
}

static void vpn_dns_drain_responses(packet_endpoint_t *endpoint, packet_endpoint_t *dns_endpoint, uint8_t *pending,
                                    size_t *pending_len, uint8_t *scratch, size_t scratch_len) {
  vpn_dns_try_write_pending_response(endpoint, pending, pending_len);
  while (*pending_len == 0u) {
    ssize_t n = packet_endpoint_read(dns_endpoint, scratch, scratch_len);
    if (n <= 0) return;
    memcpy(pending, scratch, (size_t)n);
    *pending_len = (size_t)n;
    vpn_dns_try_write_pending_response(endpoint, pending, pending_len);
  }
}

static void tunnel_close_active_session(const char *reason, int send_l2tp_teardown) {
  if (g_state.ike.esp_fd < 0) return;
  if (send_l2tp_teardown && g_state.l2tp.tunnel_id != 0) {
    if (l2tp_send_teardown(g_state.ike.esp_fd, &g_state.esp, (struct sockaddr *)&g_state.ike.peer,
                           g_state.ike.peer_len, &g_state.l2tp) != 0) {
      tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "l2tp teardown failed during close reason=%s",
                        reason != NULL ? reason : "unknown");
    }
  }
  close(g_state.ike.esp_fd);
  g_state.ike.esp_fd = -1;
  g_state.ready = 0;
}

static int set_nonblock(int fd) {
  int flags = fcntl(fd, F_GETFL, 0);
  if (flags < 0) return -1;
  return fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

static int tunnel_sanitize_mtu(int tun_mtu) {
  if (tun_mtu < 576) return 576;
  if (tun_mtu > 1500) return 1500;
  return tun_mtu;
}

int tunnel_negotiate(const char *server, const char *user, const char *password, const char *psk, int tun_mtu) {
  if (g_state.ready) {
    tunnel_close_active_session("new negotiation", 1);
  }
  g_state.ready = 0;
  atomic_store(&g_stop, 0);
  tun_mtu = tunnel_sanitize_mtu(tun_mtu);
  tunnel_log("tunnel negotiate server=%s tun_mtu=%d", server, tun_mtu);

  memset(&g_state.ike, 0, sizeof(g_state.ike));
  g_state.ike.esp_fd = -1;
  memset(&g_state.esp, 0, sizeof(g_state.esp));

  if (ikev1_connect(server, psk, &g_state.ike, &g_state.esp) != 0) {
    if (tunnel_should_stop()) {
      tunnel_engine_log(ANDROID_LOG_INFO, LOG_TAG, "tunnel negotiate canceled during IKE");
      return TUNNEL_EXIT_STOPPED;
    }
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG,
                        "ikev1_connect failed (IPsec/IKE). Filter logcat: adb logcat -s tunnel_engine:V");
    return TUNNEL_EXIT_IKE_FAILED;
  }

  memset(&g_state.l2tp, 0, sizeof(g_state.l2tp));
  if (l2tp_handshake(g_state.ike.esp_fd, &g_state.esp,
                     (struct sockaddr *)&g_state.ike.peer, g_state.ike.peer_len,
                     &g_state.l2tp) != 0) {
    if (tunnel_should_stop()) {
      tunnel_engine_log(ANDROID_LOG_INFO, LOG_TAG, "tunnel negotiate canceled during L2TP");
      tunnel_close_active_session("canceled during L2TP", 0);
      return TUNNEL_EXIT_STOPPED;
    }
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "L2TP handshake failed");
    tunnel_close_active_session("L2TP handshake failed", 0);
    return TUNNEL_EXIT_L2TP_FAILED;
  }

  memset(&g_state.ppp, 0, sizeof(g_state.ppp));
  if (ppp_negotiate(g_state.ike.esp_fd, &g_state.esp,
                    (struct sockaddr *)&g_state.ike.peer, g_state.ike.peer_len,
                    &g_state.l2tp, user, password, tun_mtu, &g_state.ppp) != 0) {
    if (tunnel_should_stop()) {
      tunnel_engine_log(ANDROID_LOG_INFO, LOG_TAG, "tunnel negotiate canceled during PPP");
      tunnel_close_active_session("canceled during PPP", 1);
      return TUNNEL_EXIT_STOPPED;
    }
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "PPP negotiation failed");
    tunnel_close_active_session("PPP negotiation failed", 1);
    return TUNNEL_EXIT_PPP_FAILED;
  }

  g_state.ready = 1;
  tunnel_log("tunnel negotiate ok (IKE+L2TP+PPP)");
  return TUNNEL_EXIT_OK;
}

void tunnel_negotiated_client_ipv4(uint8_t out[4]) {
  if (out == NULL) return;
  memcpy(out, g_state.ppp.local_ip, 4);
}

void tunnel_negotiated_dns_ipv4(uint8_t primary_out[4], uint8_t secondary_out[4]) {
  if (primary_out != NULL) memcpy(primary_out, g_state.ppp.primary_dns, 4);
  if (secondary_out != NULL) memcpy(secondary_out, g_state.ppp.secondary_dns, 4);
}

/*
 * Run the negotiated tunnel data plane for one packet endpoint.
 *
 * Preconditions:
 * - tunnel_negotiate() completed successfully and set g_state.ready.
 * - endpoint is initialized and supports packet_endpoint_read/write/poll_fd.
 * - tun_fd is a valid TUN descriptor for TUN-backed mode, or < 0 for proxy-only mode.
 *
 * Main phases:
 * 1) Initialization: clear g_stop, optionally enable VPN DNS interception bridge state.
 * 2) Poll loop: wait on endpoint + ESP socket (+ DNS queue when enabled).
 * 3) Packet routing: endpoint->PPP/ESP egress, ESP->L2TP/PPP ingress, optional DNS intercept queueing.
 * 4) Shutdown: close active session, clear DNS bridge atomics, destroy DNS queue endpoint state.
 *
 * Stop and return behavior:
 * - Loop runs until g_stop is set, or peer teardown is reported by l2tp_dispatch_incoming().
 * - Returns TUNNEL_EXIT_BAD_ARGS when called before negotiation.
 * - Returns TUNNEL_EXIT_POLL_ERROR on poll failure paths.
 * - Returns TUNNEL_EXIT_OK after normal/remote-requested shutdown cleanup.
 *
 * Shared side effects:
 * - Uses atomics g_stop, g_vpn_dns_bridge_active, g_vpn_dns_tun_fd.
 * - May call tunnel_close_active_session(), which closes ESP fd and clears g_state.ready.
 */
static int tunnel_run_loop_with_endpoint(int tun_fd, packet_endpoint_t *endpoint) {
  /* Readiness guard: run loop is only valid after negotiation state is established. */
  if (!g_state.ready) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "tunnel_run_loop: not negotiated");
    return TUNNEL_EXIT_BAD_ARGS;
  }
  /* Each invocation starts with cooperative stop cleared. */
  atomic_store(&g_stop, 0);
  int vpn_dns_enabled_for_loop = 0;
  packet_endpoint_t vpn_dns_endpoint;
  /* Activate DNS intercept bridge only when TUN exists and feature flag is enabled. */
  if (tun_fd >= 0 && atomic_load(&g_vpn_dns_intercept_enabled)) {
    if (packet_endpoint_init_proxy_queue(&vpn_dns_endpoint, &g_vpn_dns_queue) == 0) {
      atomic_store(&g_vpn_dns_tun_fd, tun_fd);
      atomic_store(&g_vpn_dns_bridge_active, 1);
      vpn_dns_enabled_for_loop = 1;
      tunnel_engine_log(ANDROID_LOG_INFO, LOG_TAG, "vpn dns intercept active");
    } else {
      tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "vpn dns intercept disabled: queue init errno=%d", errno);
    }
  }

  const int endpoint_fd = packet_endpoint_poll_fd(endpoint);
  const int vpn_dns_fd = vpn_dns_enabled_for_loop ? packet_endpoint_poll_fd(&vpn_dns_endpoint) : -1;
  /* Poll fds are prepared once; loop mutates only events/revents and pending state. */
  if ((endpoint_fd >= 0 && set_nonblock(endpoint_fd) != 0) || set_nonblock(g_state.ike.esp_fd) != 0) {
    tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "nonblock failed: errno=%d", errno);
  }

  tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "tunnel poll loop: endpoint=%s fd=%d esp_fd=%d udp_encap=%d",
                    endpoint != NULL && endpoint->name != NULL ? endpoint->name : "unknown", endpoint_fd,
                    g_state.ike.esp_fd, g_state.esp.udp_encap ? 1 : 0);
  if (tun_fd >= 0) {
    engine_notify_tunnel_ready("TUN interface ready; tunnel loop active");
  }

  uint8_t tun_buf[65536];
  uint8_t esp_buf[65536];
  uint8_t vpn_dns_response_buf[65536];
  uint8_t vpn_dns_pending_response[65536];
  size_t vpn_dns_pending_response_len = 0u;
  time_t last_outbound_to_peer = time(NULL);

  /* Data-plane loop: handle endpoint egress, ESP ingress, and optional DNS bridge traffic. */
  while (!atomic_load(&g_stop)) {
    if (vpn_dns_enabled_for_loop && vpn_dns_pending_response_len > 0u) {
      vpn_dns_try_write_pending_response(endpoint, vpn_dns_pending_response, &vpn_dns_pending_response_len);
    }
    struct pollfd fds[3];
    fds[0].fd = endpoint_fd;
    fds[0].events = POLLIN | (vpn_dns_pending_response_len > 0u ? POLLOUT : 0);
    fds[1].fd = g_state.ike.esp_fd;
    fds[1].events = POLLIN;
    fds[2].fd = vpn_dns_enabled_for_loop ? vpn_dns_fd : -1;
    fds[2].events = POLLIN;
    int pr = poll(fds, vpn_dns_enabled_for_loop ? 3u : 2u, 500);
    if (pr < 0) {
      if (errno == EINTR) continue;
      /* Poll failure is terminal: close session first, then unwind DNS bridge state. */
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "poll failed errno=%d, closing ESP fd", errno);
      tunnel_close_active_session("poll error", 1);
      if (vpn_dns_enabled_for_loop) {
        atomic_store(&g_vpn_dns_bridge_active, 0);
        atomic_store(&g_vpn_dns_tun_fd, -1);
        packet_endpoint_destroy_proxy_queue(&g_vpn_dns_queue);
      }
      return TUNNEL_EXIT_POLL_ERROR;
    }
    if (pr == 0) {
      /* Idle tick: emit periodic counters and send NAT-T keepalive when due. */
      engine_dp_maybe_log_summary(time(NULL));
      if (g_state.esp.udp_encap && g_state.esp.enc_key_len) {
        time_t now = time(NULL);
        if (nat_t_keepalive_is_due(now, last_outbound_to_peer, NAT_T_KEEPALIVE_INTERVAL_SECS)) {
          uint8_t keepalive[1];
          const size_t keepalive_len = nat_t_keepalive_write_probe(keepalive, sizeof(keepalive));
          if (keepalive_len != 0u) {
            ssize_t sent = send(g_state.ike.esp_fd, keepalive, keepalive_len, 0);
            if (sent == (ssize_t)keepalive_len) {
              last_outbound_to_peer = now;
            } else {
              tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG,
                                "nat-t keepalive send failed sent=%zd want=%zu errno=%d", sent, keepalive_len,
                                errno);
            }
          }
        }
      }
      continue;
    }

    if (vpn_dns_enabled_for_loop && (fds[2].revents & POLLIN)) {
      vpn_dns_drain_responses(endpoint, &vpn_dns_endpoint, vpn_dns_pending_response, &vpn_dns_pending_response_len,
                              vpn_dns_response_buf, sizeof(vpn_dns_response_buf));
    }

    if (vpn_dns_enabled_for_loop && (fds[0].revents & POLLOUT) && vpn_dns_pending_response_len > 0u) {
      vpn_dns_try_write_pending_response(endpoint, vpn_dns_pending_response, &vpn_dns_pending_response_len);
    }

    if (fds[0].revents & POLLIN) {
      /* Endpoint->peer egress path, with optional DNS query interception before PPP encapsulation. */
      ssize_t n = packet_endpoint_read(endpoint, tun_buf, sizeof(tun_buf));
      if (n > 0) {
        engine_dp_note_tun_rx();
        if (vpn_dns_enabled_for_loop && vpn_dns_intercept_query(tun_buf, (size_t)n)) {
          continue;
        }
        if (ppp_encapsulate_and_send(g_state.ike.esp_fd, &g_state.esp,
                                     (struct sockaddr *)&g_state.ike.peer,
                                     g_state.ike.peer_len, &g_state.l2tp,
                                     &g_state.ppp, tun_buf, (size_t)n) < 0) {
          engine_dp_note_encap_fail();
          tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "egress failed");
        } else {
          engine_dp_note_encap_ok();
          last_outbound_to_peer = time(NULL);
        }
      }
    }

    if (fds[1].revents & POLLIN) {
      /* Peer->endpoint ingress path: ESP receive, decrypt, then L2TP/PPP dispatch. */
      ssize_t n = recv(g_state.ike.esp_fd, esp_buf, sizeof(esp_buf), 0);
      if (n > 0) {
        engine_dp_note_esp_rx();
        if (g_state.esp.udp_encap && esp_is_nat_keepalive_probe(esp_buf, (size_t)n)) {
          engine_dp_maybe_log_summary(time(NULL));
          continue;
        }
        uint8_t plain[65536];
        size_t plain_len = sizeof(plain);
        if (esp_try_decrypt(&g_state.esp, esp_buf, (size_t)n, plain, &plain_len) == 0 && plain_len > 0) {
          engine_dp_note_esp_plain_ok();
          int dispatch_rc =
              l2tp_dispatch_incoming(g_state.ike.esp_fd, &g_state.esp, (struct sockaddr *)&g_state.ike.peer,
                                     g_state.ike.peer_len, &g_state.l2tp, plain, plain_len, endpoint, &g_state.ppp);
          if (dispatch_rc == L2TP_DISPATCH_REMOTE_CLOSED) {
            /* Remote requested teardown; break to shared cleanup path below. */
            tunnel_engine_log(ANDROID_LOG_INFO, LOG_TAG, "peer requested L2TP tunnel teardown");
            break;
          }
        } else {
          engine_dp_note_esp_plain_fail();
          static time_t s_last_esp_plain_warn;
          time_t now = time(NULL);
          if (now - s_last_esp_plain_warn >= 3) {
            s_last_esp_plain_warn = now;
            char detail[160];
            esp_decrypt_last_fail_snprint(detail, sizeof(detail));
            tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG,
                              "esp recv n=%zd no plaintext: %s", n, detail);
          }
        }
      }
    }
    engine_dp_maybe_log_summary(time(NULL));
  }

  /* Unified shutdown path for stop flag and peer-triggered close. */
  tunnel_close_active_session("tunnel stopped", 1);
  if (vpn_dns_enabled_for_loop) {
    atomic_store(&g_vpn_dns_bridge_active, 0);
    atomic_store(&g_vpn_dns_tun_fd, -1);
    packet_endpoint_destroy_proxy_queue(&g_vpn_dns_queue);
  }
  tunnel_log("tunnel stopped");
  return TUNNEL_EXIT_OK;
}

int tunnel_run_loop(int tun_fd) {
  tun_packet_endpoint_ctx_t tun_ctx;
  packet_endpoint_t endpoint;
  packet_endpoint_init_tun(&endpoint, &tun_ctx, tun_fd);
  return tunnel_run_loop_with_endpoint(tun_fd, &endpoint);
}

int tunnel_run_proxy_loop(void) {
  if (!g_state.ready) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "tunnel_run_proxy_loop: not negotiated");
    return TUNNEL_EXIT_BAD_ARGS;
  }
  packet_endpoint_t endpoint;
  if (packet_endpoint_init_proxy_queue(&endpoint, &g_proxy_queue) != 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "proxy queue init failed errno=%d", errno);
    tunnel_close_active_session("proxy queue init failed", 1);
    return TUNNEL_EXIT_PROXY_NOT_IMPLEMENTED;
  }
  atomic_store(&g_proxy_bridge_active, 1);
  tunnel_engine_log(ANDROID_LOG_INFO, LOG_TAG,
                    "proxy-only mode negotiated; native packet bridge active on endpoint=%s",
                    endpoint.name != NULL ? endpoint.name : "unknown");
  const int rc = tunnel_run_loop_with_endpoint(-1, &endpoint);
  packet_endpoint_destroy_proxy_queue(&g_proxy_queue);
  atomic_store(&g_proxy_bridge_active, 0);
  return rc;
}

int tunnel_loop_run(int tun_fd, const char *server, const char *user, const char *password, const char *psk,
                    int tun_mtu) {
  atomic_store(&g_stop, 0);
  tunnel_log("tunnel start server=%s tun_mtu=%d", server, tunnel_sanitize_mtu(tun_mtu));
  int rc = tunnel_negotiate(server, user, password, psk, tun_mtu);
  if (rc != TUNNEL_EXIT_OK) return rc;
  return tunnel_run_loop(tun_fd);
}

int tunnel_proxy_is_bridge_active(void) { return atomic_load(&g_proxy_bridge_active) ? 1 : 0; }

int tunnel_proxy_enqueue_outbound_packet(const uint8_t *packet, size_t len) {
  if (!atomic_load(&g_proxy_bridge_active)) {
    errno = ENOSYS;
    return -1;
  }
  return packet_endpoint_proxy_enqueue_outbound(&g_proxy_queue, packet, len);
}

ssize_t tunnel_proxy_dequeue_inbound_packet(uint8_t *packet, size_t len) {
  return tunnel_proxy_dequeue_inbound_packet_wait(packet, len, 0);
}

ssize_t tunnel_proxy_dequeue_inbound_packet_wait(uint8_t *packet, size_t len, int timeout_ms) {
  if (!atomic_load(&g_proxy_bridge_active)) {
    errno = ENOSYS;
    return -1;
  }
  return packet_endpoint_proxy_dequeue_inbound_wait(&g_proxy_queue, packet, len, timeout_ms);
}

int tunnel_vpn_dns_set_intercept_ipv4(const char *ipv4) {
  if (ipv4 == NULL || ipv4[0] == '\0') {
    atomic_store(&g_vpn_dns_intercept_enabled, 0);
    return 0;
  }
  uint8_t parsed[4];
  if (parse_ipv4_literal(ipv4, parsed) != 0) {
    errno = EINVAL;
    return -1;
  }
  memcpy(g_vpn_dns_ipv4, parsed, sizeof(g_vpn_dns_ipv4));
  atomic_store(&g_vpn_dns_intercept_enabled, 1);
  return 0;
}

ssize_t tunnel_vpn_dns_dequeue_query_wait(uint8_t *packet, size_t len, int timeout_ms) {
  if (!atomic_load(&g_vpn_dns_bridge_active)) {
    errno = ENOSYS;
    return -1;
  }
  return packet_endpoint_proxy_dequeue_inbound_wait(&g_vpn_dns_queue, packet, len, timeout_ms);
}

int tunnel_vpn_dns_write_response_packet(const uint8_t *packet, size_t len) {
  if (!atomic_load(&g_vpn_dns_bridge_active)) {
    errno = ENOSYS;
    return -1;
  }
  if (packet == NULL || len == 0) {
    errno = EINVAL;
    return -1;
  }
  return packet_endpoint_proxy_enqueue_outbound(&g_vpn_dns_queue, packet, len);
}

/* --- Dataplane counters (tunnel_run_loop + ppp_dispatch) --- */

static uint64_t g_dp_tun_rx;
static uint64_t g_dp_encap_ok;
static uint64_t g_dp_encap_fail;
static uint64_t g_dp_esp_rx;
static uint64_t g_dp_esp_plain_ok;
static uint64_t g_dp_esp_plain_fail;
static uint64_t g_dp_tun_ipv4_tcp_syn_out;
static uint64_t g_dp_tun_ipv4_wr;
static uint64_t g_dp_tun_ipv4_tcp_wr;
static uint64_t g_dp_tun_ipv4_udp_wr;
static uint64_t g_dp_tun_ipv4_icmp_wr;
static uint64_t g_dp_tun_ipv4_other_wr;

void engine_dp_note_tun_rx(void) { g_dp_tun_rx++; }

void engine_dp_note_encap_ok(void) { g_dp_encap_ok++; }

void engine_dp_note_encap_fail(void) { g_dp_encap_fail++; }

void engine_dp_note_esp_rx(void) { g_dp_esp_rx++; }

void engine_dp_note_esp_plain_ok(void) { g_dp_esp_plain_ok++; }

void engine_dp_note_esp_plain_fail(void) { g_dp_esp_plain_fail++; }

void engine_dp_note_tun_ipv4_outbound(const uint8_t *packet, size_t nbytes) {
  if (packet == NULL || nbytes < 40u) return;
  const uint8_t version = packet[0] >> 4;
  const size_t ihl = (size_t)(packet[0] & 0x0fu) * 4u;
  if (version != 4u || ihl < 20u || nbytes < ihl + 20u) return;
  if (packet[9] != 6u) return;
  const uint8_t flags = packet[ihl + 13u];
  const int syn = (flags & 0x02u) != 0u;
  const int ack = (flags & 0x10u) != 0u;
  if (!syn || ack) return;
  g_dp_tun_ipv4_tcp_syn_out++;
}

void engine_dp_note_tun_ipv4_written(size_t nbytes) {
  (void)nbytes;
  g_dp_tun_ipv4_wr++;
}

void engine_dp_note_tun_ipv4_protocol(uint8_t proto) {
  if (proto == 6u) {
    g_dp_tun_ipv4_tcp_wr++;
  } else if (proto == 17u) {
    g_dp_tun_ipv4_udp_wr++;
  } else if (proto == 1u) {
    g_dp_tun_ipv4_icmp_wr++;
  } else {
    g_dp_tun_ipv4_other_wr++;
  }
}

void engine_dp_maybe_log_summary(time_t now) {
  static time_t s_last;
  if (s_last == 0) s_last = now;
  if (now - s_last < 30) return;
  s_last = now;
  tunnel_engine_log(
      ANDROID_LOG_DEBUG, LOG_TAG,
      "dataplane 30s tun_rx=%llu encap_ok=%llu encap_fail=%llu esp_rx=%llu esp_plain_ok=%llu esp_plain_fail=%llu tun_tcp_syn_out=%llu tun_ipv4_wr=%llu tun_ipv4_tcp=%llu tun_ipv4_udp=%llu tun_ipv4_icmp=%llu tun_ipv4_other=%llu",
      (unsigned long long)g_dp_tun_rx, (unsigned long long)g_dp_encap_ok, (unsigned long long)g_dp_encap_fail,
      (unsigned long long)g_dp_esp_rx, (unsigned long long)g_dp_esp_plain_ok,
      (unsigned long long)g_dp_esp_plain_fail, (unsigned long long)g_dp_tun_ipv4_tcp_syn_out,
      (unsigned long long)g_dp_tun_ipv4_wr,
      (unsigned long long)g_dp_tun_ipv4_tcp_wr, (unsigned long long)g_dp_tun_ipv4_udp_wr,
      (unsigned long long)g_dp_tun_ipv4_icmp_wr, (unsigned long long)g_dp_tun_ipv4_other_wr);
}
