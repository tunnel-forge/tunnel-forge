#include "packet_endpoint.h"

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define PROXY_PACKET_MAX_LEN 65535
#define PROXY_OUTBOUND_PACKET_CAPACITY 1024u
#define PROXY_OUTBOUND_BYTE_CAPACITY (2u * 1024u * 1024u)

typedef struct packet_node {
  struct packet_node *next;
  size_t len;
  uint8_t data[];
} packet_node_t;

static packet_node_t *packet_node_new(const uint8_t *buf, size_t len) {
  if (buf == NULL || len == 0 || len > PROXY_PACKET_MAX_LEN) {
    errno = EMSGSIZE;
    return NULL;
  }
  packet_node_t *node = (packet_node_t *)malloc(sizeof(*node) + len);
  if (node == NULL) return NULL;
  node->next = NULL;
  node->len = len;
  memcpy(node->data, buf, len);
  return node;
}

static void packet_queue_push(packet_node_t **head, packet_node_t **tail, packet_node_t *node) {
  if (*tail == NULL) {
    *head = node;
    *tail = node;
    return;
  }
  (*tail)->next = node;
  *tail = node;
}

static packet_node_t *packet_queue_pop(packet_node_t **head, packet_node_t **tail) {
  packet_node_t *node = *head;
  if (node == NULL) return NULL;
  *head = node->next;
  if (*head == NULL) *tail = NULL;
  node->next = NULL;
  return node;
}

static void add_ms_to_timespec(struct timespec *ts, int timeout_ms) {
  if (ts == NULL || timeout_ms <= 0) return;
  ts->tv_sec += timeout_ms / 1000;
  ts->tv_nsec += (long)(timeout_ms % 1000) * 1000000L;
  if (ts->tv_nsec >= 1000000000L) {
    ts->tv_sec += 1;
    ts->tv_nsec -= 1000000000L;
  }
}

static int set_nonblock(int fd) {
  int flags = fcntl(fd, F_GETFL, 0);
  if (flags < 0) return -1;
  return fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

static int proxy_queue_signal(proxy_packet_queue_ctx_t *ctx) {
  uint8_t b = 1;
  ssize_t n = write(ctx->notify_pipe[1], &b, sizeof(b));
  if (n >= 0 || errno == EAGAIN || errno == EWOULDBLOCK) return 0;
  return -1;
}

static void proxy_queue_consume_signal(proxy_packet_queue_ctx_t *ctx) {
  uint8_t b;
  ssize_t n = read(ctx->notify_pipe[0], &b, sizeof(b));
  (void)n;
}

static ssize_t tun_packet_read(void *ctx, uint8_t *buf, size_t len) {
  tun_packet_endpoint_ctx_t *tun = (tun_packet_endpoint_ctx_t *)ctx;
  if (tun == NULL || tun->fd < 0 || buf == NULL || len == 0) return -1;
  return read(tun->fd, buf, len);
}

static int tun_packet_write(void *ctx, const uint8_t *buf, size_t len) {
  tun_packet_endpoint_ctx_t *tun = (tun_packet_endpoint_ctx_t *)ctx;
  if (tun == NULL || tun->fd < 0 || buf == NULL || len == 0) return -1;
  ssize_t n = write(tun->fd, buf, len);
  if (n == (ssize_t)len) return 0;
  if (n >= 0) errno = EIO;
  return -1;
}

static int tun_packet_poll_fd(void *ctx) {
  tun_packet_endpoint_ctx_t *tun = (tun_packet_endpoint_ctx_t *)ctx;
  if (tun == NULL) return -1;
  return tun->fd;
}

static ssize_t proxy_placeholder_read(void *ctx, uint8_t *buf, size_t len) {
  (void)ctx;
  (void)buf;
  (void)len;
  errno = ENOSYS;
  return -1;
}

static int proxy_placeholder_write(void *ctx, const uint8_t *buf, size_t len) {
  (void)ctx;
  (void)buf;
  (void)len;
  errno = ENOSYS;
  return -1;
}

static int proxy_placeholder_poll_fd(void *ctx) {
  (void)ctx;
  errno = ENOSYS;
  return -1;
}

static ssize_t proxy_queue_read(void *ctx, uint8_t *buf, size_t len) {
  proxy_packet_queue_ctx_t *queue = (proxy_packet_queue_ctx_t *)ctx;
  if (queue == NULL || buf == NULL || len == 0) {
    errno = EINVAL;
    return -1;
  }
  pthread_mutex_lock(&queue->mutex);
  packet_node_t **outbound_head = (packet_node_t **)&queue->outbound_head;
  packet_node_t **outbound_tail = (packet_node_t **)&queue->outbound_tail;
  packet_node_t *node = packet_queue_pop(outbound_head, outbound_tail);
  if (node == NULL) {
    pthread_mutex_unlock(&queue->mutex);
    errno = EAGAIN;
    return -1;
  }
  queue->outbound_count--;
  queue->outbound_bytes -= node->len;
  proxy_queue_consume_signal(queue);
  const size_t original_len = node->len;
  const size_t copy_len = node->len > len ? len : node->len;
  memcpy(buf, node->data, copy_len);
  if (queue->outbound_count > 0) {
    (void)proxy_queue_signal(queue);
  }
  pthread_mutex_unlock(&queue->mutex);
  free(node);
  if (copy_len < original_len) {
    errno = EMSGSIZE;
    return -1;
  }
  return (ssize_t)copy_len;
}

static int proxy_queue_write(void *ctx, const uint8_t *buf, size_t len) {
  proxy_packet_queue_ctx_t *queue = (proxy_packet_queue_ctx_t *)ctx;
  if (queue == NULL || buf == NULL || len == 0) {
    errno = EINVAL;
    return -1;
  }
  packet_node_t *node = packet_node_new(buf, len);
  if (node == NULL) return -1;
  pthread_mutex_lock(&queue->mutex);
  if (queue->closing) {
    pthread_mutex_unlock(&queue->mutex);
    free(node);
    errno = ECANCELED;
    return -1;
  }
  packet_queue_push((packet_node_t **)&queue->inbound_head, (packet_node_t **)&queue->inbound_tail, node);
  queue->inbound_count++;
  queue->inbound_bytes += len;
  if (queue->inbound_count > queue->inbound_high_water) queue->inbound_high_water = queue->inbound_count;
  pthread_cond_signal(&queue->inbound_cond);
  pthread_mutex_unlock(&queue->mutex);
  return 0;
}

static int proxy_queue_poll_fd(void *ctx) {
  proxy_packet_queue_ctx_t *queue = (proxy_packet_queue_ctx_t *)ctx;
  if (queue == NULL) {
    errno = EINVAL;
    return -1;
  }
  return queue->notify_pipe[0];
}

ssize_t packet_endpoint_read(packet_endpoint_t *endpoint, uint8_t *buf, size_t len) {
  if (endpoint == NULL || endpoint->read_packet == NULL) {
    errno = EINVAL;
    return -1;
  }
  return endpoint->read_packet(endpoint->ctx, buf, len);
}

int packet_endpoint_write(packet_endpoint_t *endpoint, const uint8_t *buf, size_t len) {
  if (endpoint == NULL || endpoint->write_packet == NULL) {
    errno = EINVAL;
    return -1;
  }
  return endpoint->write_packet(endpoint->ctx, buf, len);
}

int packet_endpoint_poll_fd(packet_endpoint_t *endpoint) {
  if (endpoint == NULL || endpoint->poll_fd == NULL) {
    errno = EINVAL;
    return -1;
  }
  return endpoint->poll_fd(endpoint->ctx);
}

void packet_endpoint_init_tun(packet_endpoint_t *endpoint, tun_packet_endpoint_ctx_t *ctx, int tun_fd) {
  if (endpoint == NULL || ctx == NULL) return;
  memset(ctx, 0, sizeof(*ctx));
  ctx->fd = tun_fd;
  endpoint->ctx = ctx;
  endpoint->read_packet = tun_packet_read;
  endpoint->write_packet = tun_packet_write;
  endpoint->poll_fd = tun_packet_poll_fd;
  endpoint->name = "tun";
}

void packet_endpoint_init_proxy_placeholder(packet_endpoint_t *endpoint) {
  if (endpoint == NULL) return;
  endpoint->ctx = NULL;
  endpoint->read_packet = proxy_placeholder_read;
  endpoint->write_packet = proxy_placeholder_write;
  endpoint->poll_fd = proxy_placeholder_poll_fd;
  endpoint->name = "proxy-placeholder";
}

int packet_endpoint_init_proxy_queue(packet_endpoint_t *endpoint, proxy_packet_queue_ctx_t *ctx) {
  if (endpoint == NULL || ctx == NULL) {
    errno = EINVAL;
    return -1;
  }
  memset(ctx, 0, sizeof(*ctx));
  ctx->notify_pipe[0] = -1;
  ctx->notify_pipe[1] = -1;
  if (pthread_mutex_init(&ctx->mutex, NULL) != 0) {
    errno = EBUSY;
    return -1;
  }
  if (pthread_cond_init(&ctx->inbound_cond, NULL) != 0) {
    pthread_mutex_destroy(&ctx->mutex);
    errno = EBUSY;
    return -1;
  }
  if (pthread_cond_init(&ctx->inbound_idle_cond, NULL) != 0) {
    pthread_cond_destroy(&ctx->inbound_cond);
    pthread_mutex_destroy(&ctx->mutex);
    errno = EBUSY;
    return -1;
  }
  if (pipe(ctx->notify_pipe) != 0) {
    pthread_cond_destroy(&ctx->inbound_idle_cond);
    pthread_cond_destroy(&ctx->inbound_cond);
    pthread_mutex_destroy(&ctx->mutex);
    return -1;
  }
  if (set_nonblock(ctx->notify_pipe[0]) != 0 || set_nonblock(ctx->notify_pipe[1]) != 0) {
    close(ctx->notify_pipe[0]);
    close(ctx->notify_pipe[1]);
    pthread_cond_destroy(&ctx->inbound_idle_cond);
    pthread_cond_destroy(&ctx->inbound_cond);
    pthread_mutex_destroy(&ctx->mutex);
    return -1;
  }
  endpoint->ctx = ctx;
  endpoint->read_packet = proxy_queue_read;
  endpoint->write_packet = proxy_queue_write;
  endpoint->poll_fd = proxy_queue_poll_fd;
  endpoint->name = "proxy-queue";
  return 0;
}

void packet_endpoint_destroy_proxy_queue(proxy_packet_queue_ctx_t *ctx) {
  if (ctx == NULL) return;
  pthread_mutex_lock(&ctx->mutex);
  ctx->closing = 1;
  pthread_cond_broadcast(&ctx->inbound_cond);
  /* Do not destroy condition variables while nativeReadProxyInboundPacket is still waking up. */
  while (ctx->inbound_waiters > 0) {
    pthread_cond_wait(&ctx->inbound_idle_cond, &ctx->mutex);
  }
  packet_node_t *node;
  while ((node = packet_queue_pop((packet_node_t **)&ctx->outbound_head, (packet_node_t **)&ctx->outbound_tail)) != NULL) free(node);
  while ((node = packet_queue_pop((packet_node_t **)&ctx->inbound_head, (packet_node_t **)&ctx->inbound_tail)) != NULL) free(node);
  ctx->outbound_count = 0;
  ctx->outbound_bytes = 0;
  ctx->inbound_count = 0;
  ctx->inbound_bytes = 0;
  pthread_mutex_unlock(&ctx->mutex);
  if (ctx->notify_pipe[0] >= 0) close(ctx->notify_pipe[0]);
  if (ctx->notify_pipe[1] >= 0) close(ctx->notify_pipe[1]);
  pthread_cond_destroy(&ctx->inbound_idle_cond);
  pthread_cond_destroy(&ctx->inbound_cond);
  pthread_mutex_destroy(&ctx->mutex);
}

int packet_endpoint_proxy_enqueue_outbound(proxy_packet_queue_ctx_t *ctx, const uint8_t *buf, size_t len) {
  if (ctx == NULL || buf == NULL || len == 0) {
    errno = EINVAL;
    return -1;
  }
  packet_node_t *node = packet_node_new(buf, len);
  if (node == NULL) return -1;
  pthread_mutex_lock(&ctx->mutex);
  if (ctx->closing) {
    pthread_mutex_unlock(&ctx->mutex);
    free(node);
    errno = ECANCELED;
    return -1;
  }
  if (ctx->outbound_count >= PROXY_OUTBOUND_PACKET_CAPACITY ||
      ctx->outbound_bytes + len > PROXY_OUTBOUND_BYTE_CAPACITY) {
    ctx->outbound_drops++;
    pthread_mutex_unlock(&ctx->mutex);
    free(node);
    errno = ENOBUFS;
    return -1;
  }
  packet_queue_push((packet_node_t **)&ctx->outbound_head, (packet_node_t **)&ctx->outbound_tail, node);
  ctx->outbound_count++;
  ctx->outbound_bytes += len;
  if (ctx->outbound_count > ctx->outbound_high_water) ctx->outbound_high_water = ctx->outbound_count;
  const int rc = proxy_queue_signal(ctx);
  pthread_mutex_unlock(&ctx->mutex);
  if (rc != 0) return -1;
  return 0;
}

int packet_endpoint_proxy_enqueue_inbound(proxy_packet_queue_ctx_t *ctx, const uint8_t *buf, size_t len) {
  return proxy_queue_write(ctx, buf, len);
}

ssize_t packet_endpoint_proxy_dequeue_inbound(proxy_packet_queue_ctx_t *ctx, uint8_t *buf, size_t len) {
  return packet_endpoint_proxy_dequeue_inbound_wait(ctx, buf, len, 0);
}

ssize_t packet_endpoint_proxy_dequeue_inbound_wait(proxy_packet_queue_ctx_t *ctx, uint8_t *buf, size_t len,
                                                   int timeout_ms) {
  if (ctx == NULL || buf == NULL || len == 0) {
    errno = EINVAL;
    return -1;
  }
  pthread_mutex_lock(&ctx->mutex);
  if (ctx->inbound_head == NULL && !ctx->closing && timeout_ms > 0) {
    /* Timed waits reduce Kotlin-side polling latency without blocking tunnel shutdown indefinitely. */
    struct timespec deadline;
    if (clock_gettime(CLOCK_REALTIME, &deadline) == 0) {
      add_ms_to_timespec(&deadline, timeout_ms);
      ctx->inbound_waiters++;
      while (ctx->inbound_head == NULL && !ctx->closing) {
        int rc = pthread_cond_timedwait(&ctx->inbound_cond, &ctx->mutex, &deadline);
        if (rc != 0) break;
      }
      ctx->inbound_waiters--;
      if (ctx->closing && ctx->inbound_waiters == 0) {
        pthread_cond_signal(&ctx->inbound_idle_cond);
      }
    }
  }
  packet_node_t *node = packet_queue_pop((packet_node_t **)&ctx->inbound_head, (packet_node_t **)&ctx->inbound_tail);
  if (node == NULL) {
    const int closing = ctx->closing;
    pthread_mutex_unlock(&ctx->mutex);
    errno = closing ? ECANCELED : EAGAIN;
    return -1;
  }
  ctx->inbound_count--;
  ctx->inbound_bytes -= node->len;
  const size_t copy_len = node->len > len ? len : node->len;
  memcpy(buf, node->data, copy_len);
  pthread_mutex_unlock(&ctx->mutex);
  const size_t original_len = node->len;
  free(node);
  if (copy_len < original_len) {
    errno = EMSGSIZE;
    return -1;
  }
  return (ssize_t)copy_len;
}
