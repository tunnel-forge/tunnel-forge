#ifndef TUNNEL_FORGE_PROXY_LWIP_H
#define TUNNEL_FORGE_PROXY_LWIP_H

#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>

#include "packet_endpoint.h"

int proxy_lwip_start(packet_endpoint_t *endpoint, const uint8_t client_ipv4[4], int mtu);
int proxy_lwip_is_running(void);
void proxy_lwip_stop(void);
void proxy_lwip_tick(void);

int proxy_lwip_open_tcp(const uint8_t remote_ipv4[4], uint16_t remote_port, int timeout_ms);
ssize_t proxy_lwip_read_tcp(int session_id, uint8_t *buf, size_t len, int timeout_ms);
ssize_t proxy_lwip_write_tcp(int session_id, const uint8_t *buf, size_t len, int timeout_ms);
void proxy_lwip_close_tcp(int session_id);

int proxy_lwip_open_udp(void);
int proxy_lwip_send_udp(int session_id, const uint8_t remote_ipv4[4], uint16_t remote_port, const uint8_t *payload,
                        size_t len);
ssize_t proxy_lwip_receive_udp(int session_id, uint8_t *buf, size_t len, uint8_t source_ipv4[4],
                               uint16_t *source_port, int timeout_ms);
void proxy_lwip_close_udp(int session_id);

#endif
