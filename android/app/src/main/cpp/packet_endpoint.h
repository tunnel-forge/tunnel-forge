#ifndef TUNNEL_FORGE_PACKET_ENDPOINT_H
#define TUNNEL_FORGE_PACKET_ENDPOINT_H

#include <stddef.h>
#include <pthread.h>
#include <stdint.h>
#include <sys/types.h>

typedef ssize_t (*packet_endpoint_read_fn)(void *ctx, uint8_t *buf, size_t len);
typedef int (*packet_endpoint_write_fn)(void *ctx, const uint8_t *buf, size_t len);
typedef int (*packet_endpoint_poll_fd_fn)(void *ctx);

typedef struct {
  void *ctx;
  packet_endpoint_read_fn read_packet;
  packet_endpoint_write_fn write_packet;
  packet_endpoint_poll_fd_fn poll_fd;
  const char *name;
} packet_endpoint_t;

typedef struct {
  int fd;
} tun_packet_endpoint_ctx_t;

typedef struct proxy_packet_queue_ctx {
  pthread_mutex_t mutex;
  void *outbound_head;
  void *outbound_tail;
  size_t outbound_count;
  void *inbound_head;
  void *inbound_tail;
  size_t inbound_count;
  int notify_pipe[2];
} proxy_packet_queue_ctx_t;

ssize_t packet_endpoint_read(packet_endpoint_t *endpoint, uint8_t *buf, size_t len);
int packet_endpoint_write(packet_endpoint_t *endpoint, const uint8_t *buf, size_t len);
int packet_endpoint_poll_fd(packet_endpoint_t *endpoint);
void packet_endpoint_init_tun(packet_endpoint_t *endpoint, tun_packet_endpoint_ctx_t *ctx, int tun_fd);
void packet_endpoint_init_proxy_placeholder(packet_endpoint_t *endpoint);
int packet_endpoint_init_proxy_queue(packet_endpoint_t *endpoint, proxy_packet_queue_ctx_t *ctx);
void packet_endpoint_destroy_proxy_queue(proxy_packet_queue_ctx_t *ctx);
int packet_endpoint_proxy_enqueue_outbound(proxy_packet_queue_ctx_t *ctx, const uint8_t *buf, size_t len);
ssize_t packet_endpoint_proxy_dequeue_inbound(proxy_packet_queue_ctx_t *ctx, uint8_t *buf, size_t len);

#endif
