#ifndef TUNNEL_FORGE_GVISOR_BRIDGE_H
#define TUNNEL_FORGE_GVISOR_BRIDGE_H

#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>

/*
 * C boundary for the Go gVisor netstack.
 *
 * Packet direction is from gVisor's perspective: inject_inbound delivers PPP/L2TP packets into
 * gVisor, and read_outbound returns packets gVisor wants sent through PPP/L2TP. TCP session IDs
 * are allocated by gVisor and remain valid until gvisor_bridge_tcp_close.
 */
int gvisor_bridge_start(const uint8_t client_ipv4[4], int mtu);
void gvisor_bridge_stop(void);
int gvisor_bridge_inject_inbound(const uint8_t *packet, size_t len);
ssize_t gvisor_bridge_read_outbound(uint8_t *packet, size_t len, int timeout_ms);

/*
 * Cancelable open uses the Android-side open_id until a positive session ID exists. Timeout
 * parameters are milliseconds and non-positive return codes are mapped by Kotlin.
 */
int gvisor_bridge_tcp_open(const uint8_t remote_ipv4[4], uint16_t port, int timeout_ms);
int gvisor_bridge_tcp_open_cancelable(int open_id, const uint8_t remote_ipv4[4], uint16_t port, int timeout_ms);
int gvisor_bridge_tcp_cancel_open(int open_id);
ssize_t gvisor_bridge_tcp_read(int session_id, uint8_t *buf, size_t len, int timeout_ms);
ssize_t gvisor_bridge_tcp_write(int session_id, const uint8_t *buf, size_t len, int timeout_ms);
void gvisor_bridge_tcp_close(int session_id);
int gvisor_bridge_stats(int *out, int count);
int gvisor_bridge_last_open_diagnostics(char *out, size_t count);
int gvisor_bridge_open_diagnostics(int open_id, char *out, size_t count);

#endif
