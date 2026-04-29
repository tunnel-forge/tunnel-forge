#ifndef TUNNEL_FORGE_ENGINE_H
#define TUNNEL_FORGE_ENGINE_H

/* Public C API for the Android tunnel: ports, exit codes, tunnel phases, logging, JavaVM. */

#include <jni.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>
#include <time.h>

#include "util_endian.h"

/** IKEv1 ISAKMP (cleartext) default port. */
#define IKE_PORT 500
/** IPsec NAT-T / IKE over UDP (RFC 3948). */
#define NAT_T_PORT 4500
/** Classic L2TP data channel port (inner UDP in ESP transport mode). */
#define L2TP_PORT 1701

/** RFC 3948 four-byte prefix value before ISAKMP on UDP 4500 (zero = non-ESP / IKE). */
#define ESP_UDP_NON_ESP_MARKER 0u

/** Return values from [tunnel_loop_run] to Kotlin / JNI. */
#define TUNNEL_EXIT_OK 0
#define TUNNEL_EXIT_IKE_FAILED 1
#define TUNNEL_EXIT_L2TP_FAILED 2
#define TUNNEL_EXIT_PPP_FAILED 3
#define TUNNEL_EXIT_POLL_ERROR 4
#define TUNNEL_EXIT_BAD_ARGS 10
#define TUNNEL_EXIT_PROXY_NOT_IMPLEMENTED 11
#define TUNNEL_EXIT_STOPPED 12

/** VpnService.protect(fd) when protection is enabled; no-op if disabled or JNI missing. */
int util_protect_fd(int fd);
void engine_set_socket_protection_enabled(int enabled);

int tunnel_loop_run(int tun_fd, const char *server, const char *user, const char *password, const char *psk,
                    int tun_mtu);

/** Phase 1: IKE + L2TP + PPP negotiation (no TUN fd needed). Call before VPN establish(). */
int tunnel_negotiate(const char *server, const char *user, const char *password, const char *psk, int tun_mtu);

/** After successful tunnel_negotiate(), copies negotiated PPP client IPv4 (same as IPCP local_ip). */
void tunnel_negotiated_client_ipv4(uint8_t out[4]);
void tunnel_negotiated_dns_ipv4(uint8_t primary_out[4], uint8_t secondary_out[4]);

/** Phase 2: poll loop over TUN + ESP. Call after VPN establish(). Requires prior tunnel_negotiate() success. */
int tunnel_run_loop(int tun_fd);

/** Proxy-only placeholder until a userspace TCP/IP stack is integrated. Requires prior tunnel_negotiate(). */
int tunnel_run_proxy_loop(void);
int tunnel_proxy_is_bridge_active(void);
int tunnel_proxy_enqueue_outbound_packet(const uint8_t *packet, size_t len);
ssize_t tunnel_proxy_dequeue_inbound_packet(uint8_t *packet, size_t len);
ssize_t tunnel_proxy_dequeue_inbound_packet_wait(uint8_t *packet, size_t len, int timeout_ms);
/** Configure DNS intercept forward address (used with tunnel VPN DNS queue). */
int tunnel_vpn_dns_set_intercept_ipv4(const char *ipv4);
/** Wait for intercepted DNS query from tunnel thread (Kotlin services it). */
ssize_t tunnel_vpn_dns_dequeue_query_wait(uint8_t *packet, size_t len, int timeout_ms);
/** Inject DNS response bytes toward TUN via tunnel loop. */
int tunnel_vpn_dns_write_response_packet(const uint8_t *packet, size_t len);
/** Cooperative stop flag (set from JNI); poll in native loops. */
int tunnel_should_stop(void);

/* Do not pass secrets as format args. */
void tunnel_log(const char *fmt, ...);

/** Log to logcat and mirror to the Flutter Logs tab (when the VPN channel is attached). */
void tunnel_engine_log(int prio, const char *tag, const char *fmt, ...) __attribute__((format(printf, 3, 4)));
void engine_notify_tunnel_ready(const char *detail);

void engine_set_java_vm(JavaVM *vm);
JavaVM *engine_get_java_vm(void);
/** Cache JNI class/method IDs; call from JNI_OnLoad or first attach. */
int engine_jni_init(JNIEnv *env);
void engine_jni_cleanup(JNIEnv *env);

/** Dataplane counters for tunnel_run_loop (optional; used from tunnel_loop.c + ppp.c). */
void engine_dp_note_tun_rx(void);
void engine_dp_note_encap_ok(void);
void engine_dp_note_encap_fail(void);
void engine_dp_note_esp_rx(void);
void engine_dp_note_esp_plain_ok(void);
void engine_dp_note_esp_plain_fail(void);
void engine_dp_note_tun_ipv4_outbound(const uint8_t *packet, size_t nbytes);
void engine_dp_note_tun_ipv4_written(size_t nbytes);
void engine_dp_note_tun_ipv4_protocol(uint8_t proto);
void engine_dp_maybe_log_summary(time_t now);

#endif
