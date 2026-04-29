#ifndef TUNNEL_FORGE_PACKET_ENDPOINT_H
#define TUNNEL_FORGE_PACKET_ENDPOINT_H

/*
 * Abstraction for reading/writing IPv4 frames: TUN fd, in-process proxy queues, or placeholders.
 * Used by tunnel_run_loop and proxy/DNS bridge paths. Callbacks run on the tunnel thread unless
 * noted; proxy queue APIs synchronize with mutex/condvar.
 */

#include <stddef.h>
#include <pthread.h>
#include <stdint.h>
#include <sys/types.h>

/** Read up to len bytes; return bytes read, 0 if would-block, or -1 on error (errno set). */
typedef ssize_t (*packet_endpoint_read_fn)(void *ctx, uint8_t *buf, size_t len);
/** Write full frame; return 0 on success, -1 on error (errno set, e.g. EAGAIN). */
typedef int (*packet_endpoint_write_fn)(void *ctx, const uint8_t *buf, size_t len);
/** Return pollable fd for POLLIN/POLLOUT, or -1 if not pollable. */
typedef int (*packet_endpoint_poll_fd_fn)(void *ctx);

typedef struct {
  void *ctx;
  packet_endpoint_read_fn read_packet;
  packet_endpoint_write_fn write_packet;
  packet_endpoint_poll_fd_fn poll_fd;
  const char *name;
} packet_endpoint_t;

/** Holds Android TUN fd for packet_endpoint_init_tun(). */
typedef struct {
  int fd;
} tun_packet_endpoint_ctx_t;

/** Lock-protected FIFOs + pipe for poll wakeup; see packet_endpoint.c for capacity limits. */
typedef struct proxy_packet_queue_ctx {
  pthread_mutex_t mutex;
  pthread_cond_t inbound_cond;
  pthread_cond_t inbound_idle_cond;
  size_t inbound_waiters;
  void *outbound_head;
  void *outbound_tail;
  size_t outbound_count;
  size_t outbound_bytes;
  size_t outbound_high_water;
  size_t outbound_drops;
  void *inbound_head;
  void *inbound_tail;
  size_t inbound_count;
  size_t inbound_bytes;
  size_t inbound_high_water;
  size_t inbound_drops;
  int notify_pipe[2];
  int closing;
} proxy_packet_queue_ctx_t;

ssize_t packet_endpoint_read(packet_endpoint_t *endpoint, uint8_t *buf, size_t len);
int packet_endpoint_write(packet_endpoint_t *endpoint, const uint8_t *buf, size_t len);
int packet_endpoint_poll_fd(packet_endpoint_t *endpoint);
/** Bind endpoint to TUN @p tun_fd; @p ctx must outlive the endpoint. */
void packet_endpoint_init_tun(packet_endpoint_t *endpoint, tun_packet_endpoint_ctx_t *ctx, int tun_fd);
/** No-op endpoint (proxy stack not wired); read/write fail. */
void packet_endpoint_init_proxy_placeholder(packet_endpoint_t *endpoint);
/** Initialize @p ctx and wire endpoint to proxy queues; caller zeroes ctx first. */
int packet_endpoint_init_proxy_queue(packet_endpoint_t *endpoint, proxy_packet_queue_ctx_t *ctx);
void packet_endpoint_destroy_proxy_queue(proxy_packet_queue_ctx_t *ctx);
/** Kotlin/app -> tunnel direction. May drop when over high-water. Returns 0 or -1. */
int packet_endpoint_proxy_enqueue_outbound(proxy_packet_queue_ctx_t *ctx, const uint8_t *buf, size_t len);
/** Tunnel -> app direction (e.g. synthetic packets). */
int packet_endpoint_proxy_enqueue_inbound(proxy_packet_queue_ctx_t *ctx, const uint8_t *buf, size_t len);
ssize_t packet_endpoint_proxy_dequeue_inbound(proxy_packet_queue_ctx_t *ctx, uint8_t *buf, size_t len);
/** Blocking dequeue with absolute timeout in ms; returns length, 0 if idle closed, -1 on error/timeout. */
ssize_t packet_endpoint_proxy_dequeue_inbound_wait(proxy_packet_queue_ctx_t *ctx, uint8_t *buf, size_t len,
                                                   int timeout_ms);

#endif
