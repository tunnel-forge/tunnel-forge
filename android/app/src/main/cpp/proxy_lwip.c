#include "proxy_lwip.h"

#include "engine.h"

#include <android/log.h>
#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "lwip/err.h"
#include "lwip/init.h"
#include "lwip/ip4.h"
#include "lwip/ip4_addr.h"
#include "lwip/netif.h"
#include "lwip/pbuf.h"
#include "lwip/tcp.h"
#include "lwip/timeouts.h"
#include "lwip/udp.h"

#define LOG_TAG "proxy_lwip"
#define MAX_SESSIONS 256
#define MAX_PACKET_LEN 65535u
#define DEFAULT_TIMEOUT_MS 30000

typedef struct frame_node {
  struct frame_node *next;
  size_t len;
  uint8_t data[];
} frame_node_t;

typedef struct byte_node {
  struct byte_node *next;
  size_t len;
  size_t off;
  uint8_t data[];
} byte_node_t;

typedef struct udp_datagram_node {
  struct udp_datagram_node *next;
  size_t len;
  uint8_t source_ipv4[4];
  uint16_t source_port;
  uint8_t data[];
} udp_datagram_node_t;

typedef enum {
  LWIP_SESSION_CONNECTING = 0,
  LWIP_SESSION_CONNECTED = 1,
  LWIP_SESSION_FAILED = 2,
  LWIP_SESSION_CLOSED = 3,
} lwip_session_state_t;

typedef struct {
  int id;
  int used;
  lwip_session_state_t state;
  err_t err;
  struct tcp_pcb *pcb;
  byte_node_t *rx_head;
  byte_node_t *rx_tail;
  size_t rx_bytes;
  pthread_cond_t cond;
} lwip_tcp_session_t;

typedef struct {
  int id;
  int used;
  struct udp_pcb *pcb;
  udp_datagram_node_t *rx_head;
  udp_datagram_node_t *rx_tail;
  size_t rx_count;
  pthread_cond_t cond;
} lwip_udp_session_t;

typedef struct {
  pthread_mutex_t mutex;
  pthread_cond_t outbound_cond;
  struct netif netif;
  int initialized;
  int running;
  int next_session_id;
  int notify_pipe[2];
  frame_node_t *out_head;
  frame_node_t *out_tail;
  size_t out_count;
  lwip_tcp_session_t sessions[MAX_SESSIONS];
  lwip_udp_session_t udp_sessions[MAX_SESSIONS];
} proxy_lwip_state_t;

static proxy_lwip_state_t g_lwip = {
    .mutex = PTHREAD_MUTEX_INITIALIZER,
    .outbound_cond = PTHREAD_COND_INITIALIZER,
    .next_session_id = 1,
    .notify_pipe = {-1, -1},
};

static uint32_t monotonic_ms(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (uint32_t)((ts.tv_sec * 1000ull) + ((uint64_t)ts.tv_nsec / 1000000ull));
}

uint32_t sys_now(void) { return monotonic_ms(); }

static void abs_timeout(struct timespec *ts, int timeout_ms) {
  clock_gettime(CLOCK_REALTIME, ts);
  if (timeout_ms <= 0)
    timeout_ms = DEFAULT_TIMEOUT_MS;
  ts->tv_sec += timeout_ms / 1000;
  ts->tv_nsec += (long)(timeout_ms % 1000) * 1000000L;
  if (ts->tv_nsec >= 1000000000L) {
    ts->tv_sec++;
    ts->tv_nsec -= 1000000000L;
  }
}

static void signal_endpoint_locked(void) {
  if (g_lwip.notify_pipe[1] < 0)
    return;
  uint8_t b = 1;
  ssize_t n = write(g_lwip.notify_pipe[1], &b, sizeof(b));
  (void)n;
}

static void drain_endpoint_signal_locked(void) {
  if (g_lwip.notify_pipe[0] < 0)
    return;
  uint8_t b;
  ssize_t n = read(g_lwip.notify_pipe[0], &b, sizeof(b));
  (void)n;
}

static frame_node_t *frame_node_new(const uint8_t *packet, size_t len) {
  if (packet == NULL || len == 0 || len > MAX_PACKET_LEN) {
    errno = EMSGSIZE;
    return NULL;
  }
  frame_node_t *node = (frame_node_t *)malloc(sizeof(*node) + len);
  if (node == NULL)
    return NULL;
  node->next = NULL;
  node->len = len;
  memcpy(node->data, packet, len);
  return node;
}

static void frame_push_locked(frame_node_t *node) {
  if (g_lwip.out_tail == NULL) {
    g_lwip.out_head = node;
    g_lwip.out_tail = node;
  } else {
    g_lwip.out_tail->next = node;
    g_lwip.out_tail = node;
  }
  g_lwip.out_count++;
  signal_endpoint_locked();
}

static frame_node_t *frame_pop_locked(void) {
  frame_node_t *node = g_lwip.out_head;
  if (node == NULL)
    return NULL;
  g_lwip.out_head = node->next;
  if (g_lwip.out_head == NULL)
    g_lwip.out_tail = NULL;
  node->next = NULL;
  if (g_lwip.out_count > 0)
    g_lwip.out_count--;
  drain_endpoint_signal_locked();
  if (g_lwip.out_head != NULL)
    signal_endpoint_locked();
  return node;
}

static void free_rx_queue(lwip_tcp_session_t *session) {
  byte_node_t *node = session->rx_head;
  while (node != NULL) {
    byte_node_t *next = node->next;
    free(node);
    node = next;
  }
  session->rx_head = NULL;
  session->rx_tail = NULL;
  session->rx_bytes = 0;
}

static void free_udp_rx_queue(lwip_udp_session_t *session) {
  udp_datagram_node_t *node = session->rx_head;
  while (node != NULL) {
    udp_datagram_node_t *next = node->next;
    free(node);
    node = next;
  }
  session->rx_head = NULL;
  session->rx_tail = NULL;
  session->rx_count = 0;
}

static lwip_tcp_session_t *session_by_id_locked(int id) {
  for (size_t i = 0; i < MAX_SESSIONS; i++) {
    if (g_lwip.sessions[i].used && g_lwip.sessions[i].id == id)
      return &g_lwip.sessions[i];
  }
  return NULL;
}

static lwip_udp_session_t *udp_session_by_id_locked(int id) {
  for (size_t i = 0; i < MAX_SESSIONS; i++) {
    if (g_lwip.udp_sessions[i].used && g_lwip.udp_sessions[i].id == id)
      return &g_lwip.udp_sessions[i];
  }
  return NULL;
}

static lwip_tcp_session_t *session_alloc_locked(void) {
  for (size_t i = 0; i < MAX_SESSIONS; i++) {
    lwip_tcp_session_t *s = &g_lwip.sessions[i];
    if (!s->used) {
      memset(s, 0, sizeof(*s));
      s->used = 1;
      s->id = g_lwip.next_session_id++;
      if (g_lwip.next_session_id <= 0)
        g_lwip.next_session_id = 1;
      s->state = LWIP_SESSION_CONNECTING;
      s->err = ERR_OK;
      pthread_cond_init(&s->cond, NULL);
      return s;
    }
  }
  errno = ENFILE;
  return NULL;
}

static lwip_udp_session_t *udp_session_alloc_locked(void) {
  for (size_t i = 0; i < MAX_SESSIONS; i++) {
    lwip_udp_session_t *s = &g_lwip.udp_sessions[i];
    if (!s->used) {
      memset(s, 0, sizeof(*s));
      s->used = 1;
      s->id = g_lwip.next_session_id++;
      if (g_lwip.next_session_id <= 0)
        g_lwip.next_session_id = 1;
      pthread_cond_init(&s->cond, NULL);
      return s;
    }
  }
  errno = ENFILE;
  return NULL;
}

static void session_release_locked(lwip_tcp_session_t *s) {
  if (s == NULL || !s->used)
    return;
  if (s->pcb != NULL) {
    tcp_arg(s->pcb, NULL);
    tcp_recv(s->pcb, NULL);
    tcp_sent(s->pcb, NULL);
    tcp_poll(s->pcb, NULL, 0);
    tcp_err(s->pcb, NULL);
    tcp_close(s->pcb);
    s->pcb = NULL;
  }
  free_rx_queue(s);
  pthread_cond_broadcast(&s->cond);
  pthread_cond_destroy(&s->cond);
  memset(s, 0, sizeof(*s));
}

static void udp_session_release_locked(lwip_udp_session_t *s) {
  if (s == NULL || !s->used)
    return;
  if (s->pcb != NULL) {
    udp_recv(s->pcb, NULL, NULL);
    udp_remove(s->pcb);
    s->pcb = NULL;
  }
  free_udp_rx_queue(s);
  pthread_cond_broadcast(&s->cond);
  pthread_cond_destroy(&s->cond);
  memset(s, 0, sizeof(*s));
}

static err_t netif_output(struct netif *netif, struct pbuf *p, const ip4_addr_t *ipaddr) {
  (void)netif;
  (void)ipaddr;
  if (p == NULL || p->tot_len == 0)
    return ERR_ARG;
  uint8_t *packet = (uint8_t *)malloc(p->tot_len);
  if (packet == NULL)
    return ERR_MEM;
  pbuf_copy_partial(p, packet, p->tot_len, 0);
  frame_node_t *node = frame_node_new(packet, p->tot_len);
  free(packet);
  if (node == NULL)
    return ERR_MEM;
  frame_push_locked(node);
  return ERR_OK;
}

static err_t netif_init_cb(struct netif *netif) {
  netif->name[0] = 't';
  netif->name[1] = 'f';
  netif->output = netif_output;
  netif->mtu = 1450;
  netif->flags = NETIF_FLAG_UP | NETIF_FLAG_LINK_UP;
  return ERR_OK;
}

static ssize_t endpoint_read(void *ctx, uint8_t *buf, size_t len) {
  (void)ctx;
  if (buf == NULL || len == 0) {
    errno = EINVAL;
    return -1;
  }
  pthread_mutex_lock(&g_lwip.mutex);
  frame_node_t *node = frame_pop_locked();
  pthread_mutex_unlock(&g_lwip.mutex);
  if (node == NULL) {
    errno = EAGAIN;
    return -1;
  }
  if (node->len > len) {
    free(node);
    errno = EMSGSIZE;
    return -1;
  }
  memcpy(buf, node->data, node->len);
  ssize_t out = (ssize_t)node->len;
  free(node);
  return out;
}

static int endpoint_write(void *ctx, const uint8_t *buf, size_t len) {
  (void)ctx;
  if (buf == NULL || len == 0 || len > MAX_PACKET_LEN) {
    errno = EINVAL;
    return -1;
  }
  pthread_mutex_lock(&g_lwip.mutex);
  if (!g_lwip.running) {
    pthread_mutex_unlock(&g_lwip.mutex);
    errno = ENOSYS;
    return -1;
  }
  struct pbuf *p = pbuf_alloc(PBUF_RAW, (u16_t)len, PBUF_POOL);
  if (p == NULL) {
    pthread_mutex_unlock(&g_lwip.mutex);
    errno = ENOMEM;
    return -1;
  }
  pbuf_take(p, buf, (u16_t)len);
  err_t err = g_lwip.netif.input(p, &g_lwip.netif);
  if (err != ERR_OK) {
    pbuf_free(p);
    pthread_mutex_unlock(&g_lwip.mutex);
    errno = EIO;
    return -1;
  }
  sys_check_timeouts();
  pthread_mutex_unlock(&g_lwip.mutex);
  return 0;
}

static int endpoint_poll_fd(void *ctx) {
  (void)ctx;
  return g_lwip.notify_pipe[0];
}

static err_t tcp_connected_cb(void *arg, struct tcp_pcb *pcb, err_t err) {
  (void)pcb;
  lwip_tcp_session_t *s = (lwip_tcp_session_t *)arg;
  if (s == NULL)
    return ERR_ABRT;
  s->err = err;
  s->state = err == ERR_OK ? LWIP_SESSION_CONNECTED : LWIP_SESSION_FAILED;
  pthread_cond_broadcast(&s->cond);
  return ERR_OK;
}

static err_t tcp_recv_cb(void *arg, struct tcp_pcb *pcb, struct pbuf *p, err_t err) {
  lwip_tcp_session_t *s = (lwip_tcp_session_t *)arg;
  if (s == NULL)
    return ERR_ABRT;
  if (p == NULL) {
    s->state = LWIP_SESSION_CLOSED;
    pthread_cond_broadcast(&s->cond);
    return ERR_OK;
  }
  if (err != ERR_OK) {
    pbuf_free(p);
    s->state = LWIP_SESSION_FAILED;
    s->err = err;
    pthread_cond_broadcast(&s->cond);
    return ERR_OK;
  }
  byte_node_t *node = (byte_node_t *)malloc(sizeof(*node) + p->tot_len);
  if (node == NULL) {
    pbuf_free(p);
    return ERR_MEM;
  }
  node->next = NULL;
  node->len = p->tot_len;
  node->off = 0;
  pbuf_copy_partial(p, node->data, p->tot_len, 0);
  if (s->rx_tail == NULL) {
    s->rx_head = node;
    s->rx_tail = node;
  } else {
    s->rx_tail->next = node;
    s->rx_tail = node;
  }
  s->rx_bytes += node->len;
  tcp_recved(pcb, p->tot_len);
  pbuf_free(p);
  pthread_cond_broadcast(&s->cond);
  return ERR_OK;
}

static err_t tcp_sent_cb(void *arg, struct tcp_pcb *pcb, u16_t len) {
  (void)pcb;
  (void)len;
  lwip_tcp_session_t *s = (lwip_tcp_session_t *)arg;
  if (s != NULL)
    pthread_cond_broadcast(&s->cond);
  return ERR_OK;
}

static err_t tcp_poll_cb(void *arg, struct tcp_pcb *pcb) {
  (void)pcb;
  lwip_tcp_session_t *s = (lwip_tcp_session_t *)arg;
  if (s != NULL)
    pthread_cond_broadcast(&s->cond);
  return ERR_OK;
}

static void tcp_err_cb(void *arg, err_t err) {
  lwip_tcp_session_t *s = (lwip_tcp_session_t *)arg;
  if (s == NULL)
    return;
  s->pcb = NULL;
  s->err = err;
  s->state = LWIP_SESSION_FAILED;
  pthread_cond_broadcast(&s->cond);
}

static void udp_recv_cb(void *arg, struct udp_pcb *pcb, struct pbuf *p, const ip_addr_t *addr, u16_t port) {
  (void)pcb;
  lwip_udp_session_t *s = (lwip_udp_session_t *)arg;
  if (s == NULL || p == NULL || addr == NULL) {
    if (p != NULL)
      pbuf_free(p);
    return;
  }
  udp_datagram_node_t *node = (udp_datagram_node_t *)malloc(sizeof(*node) + p->tot_len);
  if (node == NULL) {
    pbuf_free(p);
    return;
  }
  node->next = NULL;
  node->len = p->tot_len;
  node->source_port = port;
  const ip4_addr_t *ip4 = ip_2_ip4(addr);
  const uint32_t source = ip4_addr_get_u32(ip4);
  node->source_ipv4[0] = ip4_addr1_16(ip4);
  node->source_ipv4[1] = ip4_addr2_16(ip4);
  node->source_ipv4[2] = ip4_addr3_16(ip4);
  node->source_ipv4[3] = ip4_addr4_16(ip4);
  (void)source;
  pbuf_copy_partial(p, node->data, p->tot_len, 0);
  pbuf_free(p);
  if (s->rx_tail == NULL) {
    s->rx_head = node;
    s->rx_tail = node;
  } else {
    s->rx_tail->next = node;
    s->rx_tail = node;
  }
  s->rx_count++;
  pthread_cond_broadcast(&s->cond);
}

int proxy_lwip_start(packet_endpoint_t *endpoint, const uint8_t client_ipv4[4], int mtu) {
  if (endpoint == NULL || client_ipv4 == NULL) {
    errno = EINVAL;
    return -1;
  }
  pthread_mutex_lock(&g_lwip.mutex);
  if (!g_lwip.initialized) {
    lwip_init();
    g_lwip.initialized = 1;
  }
  if (g_lwip.notify_pipe[0] < 0 && pipe(g_lwip.notify_pipe) != 0) {
    pthread_mutex_unlock(&g_lwip.mutex);
    return -1;
  }
  ip4_addr_t ipaddr;
  ip4_addr_t netmask;
  ip4_addr_t gw;
  IP4_ADDR(&ipaddr, client_ipv4[0], client_ipv4[1], client_ipv4[2], client_ipv4[3]);
  IP4_ADDR(&netmask, 255, 255, 255, 255);
  IP4_ADDR(&gw, 0, 0, 0, 0);
  if (g_lwip.netif.input != NULL) {
    netif_remove(&g_lwip.netif);
  }
  memset(&g_lwip.netif, 0, sizeof(g_lwip.netif));
  if (netif_add(&g_lwip.netif, &ipaddr, &netmask, &gw, NULL, netif_init_cb, ip4_input) == NULL) {
    pthread_mutex_unlock(&g_lwip.mutex);
    errno = EIO;
    return -1;
  }
  g_lwip.netif.mtu = (u16_t)(mtu > 576 && mtu <= 1500 ? mtu : 1450);
  netif_set_default(&g_lwip.netif);
  netif_set_up(&g_lwip.netif);
  netif_set_link_up(&g_lwip.netif);
  g_lwip.running = 1;
  endpoint->ctx = NULL;
  endpoint->read_packet = endpoint_read;
  endpoint->write_packet = endpoint_write;
  endpoint->poll_fd = endpoint_poll_fd;
  endpoint->name = "lwip-proxy";
  tunnel_engine_log(ANDROID_LOG_INFO, LOG_TAG, "lwIP proxy endpoint started ip=%u.%u.%u.%u mtu=%d", client_ipv4[0],
                    client_ipv4[1], client_ipv4[2], client_ipv4[3], g_lwip.netif.mtu);
  pthread_mutex_unlock(&g_lwip.mutex);
  return 0;
}

void proxy_lwip_tick(void) {
  pthread_mutex_lock(&g_lwip.mutex);
  if (g_lwip.running)
    sys_check_timeouts();
  pthread_mutex_unlock(&g_lwip.mutex);
}

int proxy_lwip_is_running(void) {
  pthread_mutex_lock(&g_lwip.mutex);
  int running = g_lwip.running ? 1 : 0;
  pthread_mutex_unlock(&g_lwip.mutex);
  return running;
}

void proxy_lwip_stop(void) {
  pthread_mutex_lock(&g_lwip.mutex);
  g_lwip.running = 0;
  for (size_t i = 0; i < MAX_SESSIONS; i++) {
    if (g_lwip.sessions[i].used)
      session_release_locked(&g_lwip.sessions[i]);
    if (g_lwip.udp_sessions[i].used)
      udp_session_release_locked(&g_lwip.udp_sessions[i]);
  }
  frame_node_t *frame;
  while ((frame = frame_pop_locked()) != NULL)
    free(frame);
  if (g_lwip.netif.input != NULL) {
    netif_set_down(&g_lwip.netif);
    netif_remove(&g_lwip.netif);
    memset(&g_lwip.netif, 0, sizeof(g_lwip.netif));
  }
  if (g_lwip.notify_pipe[0] >= 0) {
    close(g_lwip.notify_pipe[0]);
    g_lwip.notify_pipe[0] = -1;
  }
  if (g_lwip.notify_pipe[1] >= 0) {
    close(g_lwip.notify_pipe[1]);
    g_lwip.notify_pipe[1] = -1;
  }
  pthread_mutex_unlock(&g_lwip.mutex);
}

int proxy_lwip_open_tcp(const uint8_t remote_ipv4[4], uint16_t remote_port, int timeout_ms) {
  if (remote_ipv4 == NULL || remote_port == 0) {
    errno = EINVAL;
    return -1;
  }
  pthread_mutex_lock(&g_lwip.mutex);
  if (!g_lwip.running) {
    pthread_mutex_unlock(&g_lwip.mutex);
    errno = ENOSYS;
    return -1;
  }
  lwip_tcp_session_t *s = session_alloc_locked();
  if (s == NULL) {
    pthread_mutex_unlock(&g_lwip.mutex);
    return -1;
  }
  s->pcb = tcp_new_ip_type(IPADDR_TYPE_V4);
  if (s->pcb == NULL) {
    session_release_locked(s);
    pthread_mutex_unlock(&g_lwip.mutex);
    errno = ENOMEM;
    return -1;
  }
  tcp_arg(s->pcb, s);
  tcp_recv(s->pcb, tcp_recv_cb);
  tcp_sent(s->pcb, tcp_sent_cb);
  tcp_poll(s->pcb, tcp_poll_cb, 2);
  tcp_err(s->pcb, tcp_err_cb);
  ip_addr_t target;
  IP_ADDR4(&target, remote_ipv4[0], remote_ipv4[1], remote_ipv4[2], remote_ipv4[3]);
  err_t err = tcp_connect(s->pcb, &target, remote_port, tcp_connected_cb);
  if (err != ERR_OK) {
    session_release_locked(s);
    pthread_mutex_unlock(&g_lwip.mutex);
    errno = EIO;
    return -1;
  }
  tcp_output(s->pcb);
  struct timespec deadline;
  abs_timeout(&deadline, timeout_ms);
  while (s->state == LWIP_SESSION_CONNECTING && g_lwip.running) {
    int wait_rc = pthread_cond_timedwait(&s->cond, &g_lwip.mutex, &deadline);
    sys_check_timeouts();
    if (wait_rc == ETIMEDOUT)
      break;
  }
  if (s->state != LWIP_SESSION_CONNECTED) {
    int id = s->id;
    session_release_locked(s);
    pthread_mutex_unlock(&g_lwip.mutex);
    errno = ETIMEDOUT;
    tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "lwIP TCP connect failed id=%d", id);
    return -1;
  }
  int id = s->id;
  pthread_mutex_unlock(&g_lwip.mutex);
  return id;
}

ssize_t proxy_lwip_read_tcp(int session_id, uint8_t *buf, size_t len, int timeout_ms) {
  if (buf == NULL || len == 0) {
    errno = EINVAL;
    return -1;
  }
  pthread_mutex_lock(&g_lwip.mutex);
  lwip_tcp_session_t *s = session_by_id_locked(session_id);
  if (s == NULL) {
    pthread_mutex_unlock(&g_lwip.mutex);
    errno = EBADF;
    return -1;
  }
  struct timespec deadline;
  abs_timeout(&deadline, timeout_ms);
  while (s->rx_head == NULL && s->state == LWIP_SESSION_CONNECTED && g_lwip.running) {
    int wait_rc = pthread_cond_timedwait(&s->cond, &g_lwip.mutex, &deadline);
    sys_check_timeouts();
    if (wait_rc == ETIMEDOUT)
      break;
  }
  if (s->rx_head == NULL) {
    int closed = s->state == LWIP_SESSION_CLOSED;
    pthread_mutex_unlock(&g_lwip.mutex);
    if (closed)
      return 0;
    errno = EAGAIN;
    return -1;
  }
  byte_node_t *node = s->rx_head;
  size_t copy_len = node->len - node->off;
  if (copy_len > len)
    copy_len = len;
  memcpy(buf, node->data + node->off, copy_len);
  node->off += copy_len;
  s->rx_bytes -= copy_len;
  if (node->off == node->len) {
    s->rx_head = node->next;
    if (s->rx_head == NULL)
      s->rx_tail = NULL;
    free(node);
  }
  pthread_mutex_unlock(&g_lwip.mutex);
  return (ssize_t)copy_len;
}

ssize_t proxy_lwip_write_tcp(int session_id, const uint8_t *buf, size_t len, int timeout_ms) {
  if (buf == NULL || len == 0) {
    errno = EINVAL;
    return -1;
  }
  pthread_mutex_lock(&g_lwip.mutex);
  lwip_tcp_session_t *s = session_by_id_locked(session_id);
  if (s == NULL || s->pcb == NULL || s->state != LWIP_SESSION_CONNECTED) {
    pthread_mutex_unlock(&g_lwip.mutex);
    errno = EBADF;
    return -1;
  }
  struct timespec deadline;
  abs_timeout(&deadline, timeout_ms);
  size_t written = 0;
  while (written < len && s->state == LWIP_SESSION_CONNECTED && g_lwip.running) {
    u16_t sndbuf = tcp_sndbuf(s->pcb);
    if (sndbuf == 0) {
      int wait_rc = pthread_cond_timedwait(&s->cond, &g_lwip.mutex, &deadline);
      sys_check_timeouts();
      if (wait_rc == ETIMEDOUT)
        break;
      continue;
    }
    size_t chunk = len - written;
    if (chunk > sndbuf)
      chunk = sndbuf;
    if (chunk > 0xffffu)
      chunk = 0xffffu;
    err_t err = tcp_write(s->pcb, buf + written, (u16_t)chunk, TCP_WRITE_FLAG_COPY);
    if (err == ERR_MEM) {
      pthread_cond_timedwait(&s->cond, &g_lwip.mutex, &deadline);
      sys_check_timeouts();
      continue;
    }
    if (err != ERR_OK) {
      pthread_mutex_unlock(&g_lwip.mutex);
      errno = EIO;
      return written > 0 ? (ssize_t)written : -1;
    }
    written += chunk;
    tcp_output(s->pcb);
  }
  pthread_mutex_unlock(&g_lwip.mutex);
  return written > 0 ? (ssize_t)written : -1;
}

void proxy_lwip_close_tcp(int session_id) {
  pthread_mutex_lock(&g_lwip.mutex);
  lwip_tcp_session_t *s = session_by_id_locked(session_id);
  if (s != NULL)
    session_release_locked(s);
  pthread_mutex_unlock(&g_lwip.mutex);
}

int proxy_lwip_open_udp(void) {
  pthread_mutex_lock(&g_lwip.mutex);
  if (!g_lwip.running) {
    pthread_mutex_unlock(&g_lwip.mutex);
    errno = ENOSYS;
    return -1;
  }
  lwip_udp_session_t *s = udp_session_alloc_locked();
  if (s == NULL) {
    pthread_mutex_unlock(&g_lwip.mutex);
    return -1;
  }
  s->pcb = udp_new_ip_type(IPADDR_TYPE_V4);
  if (s->pcb == NULL) {
    udp_session_release_locked(s);
    pthread_mutex_unlock(&g_lwip.mutex);
    errno = ENOMEM;
    return -1;
  }
  udp_recv(s->pcb, udp_recv_cb, s);
  int id = s->id;
  pthread_mutex_unlock(&g_lwip.mutex);
  return id;
}

int proxy_lwip_send_udp(int session_id, const uint8_t remote_ipv4[4], uint16_t remote_port, const uint8_t *payload,
                        size_t len) {
  if (remote_ipv4 == NULL || remote_port == 0 || payload == NULL || len == 0 || len > 65507u) {
    errno = EINVAL;
    return -1;
  }
  pthread_mutex_lock(&g_lwip.mutex);
  lwip_udp_session_t *s = udp_session_by_id_locked(session_id);
  if (s == NULL || s->pcb == NULL || !g_lwip.running) {
    pthread_mutex_unlock(&g_lwip.mutex);
    errno = EBADF;
    return -1;
  }
  struct pbuf *p = pbuf_alloc(PBUF_TRANSPORT, (u16_t)len, PBUF_POOL);
  if (p == NULL) {
    pthread_mutex_unlock(&g_lwip.mutex);
    errno = ENOMEM;
    return -1;
  }
  pbuf_take(p, payload, (u16_t)len);
  ip_addr_t target;
  IP_ADDR4(&target, remote_ipv4[0], remote_ipv4[1], remote_ipv4[2], remote_ipv4[3]);
  err_t err = udp_sendto(s->pcb, p, &target, remote_port);
  pbuf_free(p);
  sys_check_timeouts();
  pthread_mutex_unlock(&g_lwip.mutex);
  if (err != ERR_OK) {
    errno = EIO;
    return -1;
  }
  return 0;
}

ssize_t proxy_lwip_receive_udp(int session_id, uint8_t *buf, size_t len, uint8_t source_ipv4[4],
                               uint16_t *source_port, int timeout_ms) {
  if (buf == NULL || len == 0 || source_ipv4 == NULL || source_port == NULL) {
    errno = EINVAL;
    return -1;
  }
  pthread_mutex_lock(&g_lwip.mutex);
  lwip_udp_session_t *s = udp_session_by_id_locked(session_id);
  if (s == NULL) {
    pthread_mutex_unlock(&g_lwip.mutex);
    errno = EBADF;
    return -1;
  }
  struct timespec deadline;
  abs_timeout(&deadline, timeout_ms);
  while (s->rx_head == NULL && g_lwip.running) {
    int wait_rc = pthread_cond_timedwait(&s->cond, &g_lwip.mutex, &deadline);
    sys_check_timeouts();
    if (wait_rc == ETIMEDOUT)
      break;
  }
  udp_datagram_node_t *node = s->rx_head;
  if (node == NULL) {
    pthread_mutex_unlock(&g_lwip.mutex);
    errno = EAGAIN;
    return -1;
  }
  s->rx_head = node->next;
  if (s->rx_head == NULL)
    s->rx_tail = NULL;
  if (s->rx_count > 0)
    s->rx_count--;
  if (node->len > len) {
    free(node);
    pthread_mutex_unlock(&g_lwip.mutex);
    errno = EMSGSIZE;
    return -1;
  }
  memcpy(buf, node->data, node->len);
  memcpy(source_ipv4, node->source_ipv4, 4);
  *source_port = node->source_port;
  ssize_t out = (ssize_t)node->len;
  free(node);
  pthread_mutex_unlock(&g_lwip.mutex);
  return out;
}

void proxy_lwip_close_udp(int session_id) {
  pthread_mutex_lock(&g_lwip.mutex);
  lwip_udp_session_t *s = udp_session_by_id_locked(session_id);
  if (s != NULL)
    udp_session_release_locked(s);
  pthread_mutex_unlock(&g_lwip.mutex);
}
