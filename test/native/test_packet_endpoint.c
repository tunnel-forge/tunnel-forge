#include "packet_endpoint.h"

#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define EXPECT_OUTBOUND_PACKET_CAPACITY 1024u
#define EXPECT_OUTBOUND_BYTE_CAPACITY (2u * 1024u * 1024u)
#define MAX_PACKET_LEN 65535u

typedef struct {
  proxy_packet_queue_ctx_t *ctx;
  ssize_t rc;
  int err;
  uint8_t buf[16];
} wait_args_t;

static void *wait_inbound_thread(void *arg) {
  wait_args_t *args = (wait_args_t *)arg;
  args->rc = packet_endpoint_proxy_dequeue_inbound_wait(args->ctx, args->buf, sizeof(args->buf), 5000);
  args->err = errno;
  return NULL;
}

static int init_queue(packet_endpoint_t *endpoint, proxy_packet_queue_ctx_t *ctx) {
  if (packet_endpoint_init_proxy_queue(endpoint, ctx) != 0) {
    perror("packet_endpoint_init_proxy_queue");
    return -1;
  }
  return 0;
}

static int test_fifo_and_byte_counters(void) {
  packet_endpoint_t endpoint;
  proxy_packet_queue_ctx_t ctx;
  if (init_queue(&endpoint, &ctx) != 0) return 1;

  const uint8_t first[] = {1, 2, 3};
  const uint8_t second[] = {4, 5};
  if (packet_endpoint_proxy_enqueue_outbound(&ctx, first, sizeof(first)) != 0) return 2;
  if (packet_endpoint_proxy_enqueue_outbound(&ctx, second, sizeof(second)) != 0) return 3;
  if (ctx.outbound_count != 2u || ctx.outbound_bytes != 5u || ctx.outbound_high_water != 2u) return 4;

  uint8_t out[8] = {0};
  ssize_t n = packet_endpoint_read(&endpoint, out, sizeof(out));
  if (n != (ssize_t)sizeof(first) || memcmp(out, first, sizeof(first)) != 0) return 5;
  if (ctx.outbound_count != 1u || ctx.outbound_bytes != 2u) return 6;

  memset(out, 0, sizeof(out));
  n = packet_endpoint_read(&endpoint, out, sizeof(out));
  if (n != (ssize_t)sizeof(second) || memcmp(out, second, sizeof(second)) != 0) return 7;
  if (ctx.outbound_count != 0u || ctx.outbound_bytes != 0u) return 8;

  packet_endpoint_destroy_proxy_queue(&ctx);
  return 0;
}

static int test_udp_shaped_ipv4_packet_round_trips_unchanged(void) {
  packet_endpoint_t endpoint;
  proxy_packet_queue_ctx_t ctx;
  if (init_queue(&endpoint, &ctx) != 0) return 1;

  const uint8_t udp_packet[] = {
      0x45, 0x00, 0x00, 0x20, 0x12, 0x34, 0x40, 0x00, 0x40, 0x11, 0x00, 0x00,
      10,   0,    0,    2,    93,   184,  216,  34,   0xc3, 0x50, 0x14, 0xe9,
      0x00, 0x0c, 0x00, 0x00, 'p',  'i',  'n',  'g',
  };
  if (packet_endpoint_proxy_enqueue_outbound(&ctx, udp_packet, sizeof(udp_packet)) != 0) return 2;

  uint8_t out[sizeof(udp_packet)] = {0};
  ssize_t n = packet_endpoint_read(&endpoint, out, sizeof(out));
  if (n != (ssize_t)sizeof(udp_packet)) return 3;
  if (memcmp(out, udp_packet, sizeof(udp_packet)) != 0) return 4;

  packet_endpoint_destroy_proxy_queue(&ctx);
  return 0;
}

static int test_outbound_count_cap_returns_enobufs(void) {
  packet_endpoint_t endpoint;
  proxy_packet_queue_ctx_t ctx;
  if (init_queue(&endpoint, &ctx) != 0) return 1;

  const uint8_t packet[] = {0xaa};
  for (size_t i = 0; i < EXPECT_OUTBOUND_PACKET_CAPACITY; i++) {
    if (packet_endpoint_proxy_enqueue_outbound(&ctx, packet, sizeof(packet)) != 0) return 2;
  }
  if (packet_endpoint_proxy_enqueue_outbound(&ctx, packet, sizeof(packet)) == 0) return 3;
  if (errno != ENOBUFS) return 4;
  if (ctx.outbound_drops != 1u) return 5;
  if (ctx.outbound_high_water != EXPECT_OUTBOUND_PACKET_CAPACITY) return 6;

  packet_endpoint_destroy_proxy_queue(&ctx);
  return 0;
}

static int test_outbound_byte_cap_returns_enobufs(void) {
  packet_endpoint_t endpoint;
  proxy_packet_queue_ctx_t ctx;
  if (init_queue(&endpoint, &ctx) != 0) return 1;

  uint8_t *packet = (uint8_t *)malloc(MAX_PACKET_LEN);
  if (packet == NULL) return 2;
  memset(packet, 0x5a, MAX_PACKET_LEN);

  const size_t allowed_packets = EXPECT_OUTBOUND_BYTE_CAPACITY / MAX_PACKET_LEN;
  for (size_t i = 0; i < allowed_packets; i++) {
    if (packet_endpoint_proxy_enqueue_outbound(&ctx, packet, MAX_PACKET_LEN) != 0) return 3;
  }
  if (ctx.outbound_bytes != allowed_packets * MAX_PACKET_LEN) return 4;
  if (packet_endpoint_proxy_enqueue_outbound(&ctx, packet, MAX_PACKET_LEN) == 0) return 5;
  if (errno != ENOBUFS) return 6;
  if (ctx.outbound_drops != 1u) return 7;

  free(packet);
  packet_endpoint_destroy_proxy_queue(&ctx);
  return 0;
}

static int test_inbound_timed_dequeue_wakes_on_packet(void) {
  packet_endpoint_t endpoint;
  proxy_packet_queue_ctx_t ctx;
  if (init_queue(&endpoint, &ctx) != 0) return 1;

  wait_args_t args;
  memset(&args, 0, sizeof(args));
  args.ctx = &ctx;
  pthread_t thread;
  if (pthread_create(&thread, NULL, wait_inbound_thread, &args) != 0) return 2;
  usleep(50000);

  const uint8_t packet[] = {9, 8, 7, 6};
  if (packet_endpoint_write(&endpoint, packet, sizeof(packet)) != 0) return 3;
  if (pthread_join(thread, NULL) != 0) return 4;
  if (args.rc != (ssize_t)sizeof(packet)) return 5;
  if (memcmp(args.buf, packet, sizeof(packet)) != 0) return 6;
  if (ctx.inbound_count != 0u || ctx.inbound_bytes != 0u || ctx.inbound_high_water != 1u) return 7;

  packet_endpoint_destroy_proxy_queue(&ctx);
  return 0;
}

static int test_destroy_wakes_waiting_inbound_dequeue(void) {
  packet_endpoint_t endpoint;
  proxy_packet_queue_ctx_t ctx;
  if (init_queue(&endpoint, &ctx) != 0) return 1;

  wait_args_t args;
  memset(&args, 0, sizeof(args));
  args.ctx = &ctx;
  pthread_t thread;
  if (pthread_create(&thread, NULL, wait_inbound_thread, &args) != 0) return 2;
  usleep(50000);

  packet_endpoint_destroy_proxy_queue(&ctx);
  if (pthread_join(thread, NULL) != 0) return 3;
  if (args.rc != -1) return 4;
  if (args.err != ECANCELED) return 5;
  return 0;
}

int main(void) {
  int rc = test_fifo_and_byte_counters();
  if (rc != 0) {
    fprintf(stderr, "test_fifo_and_byte_counters failed: %d\n", rc);
    return rc;
  }
  rc = test_udp_shaped_ipv4_packet_round_trips_unchanged();
  if (rc != 0) {
    fprintf(stderr, "test_udp_shaped_ipv4_packet_round_trips_unchanged failed: %d\n", rc);
    return rc;
  }
  rc = test_outbound_count_cap_returns_enobufs();
  if (rc != 0) {
    fprintf(stderr, "test_outbound_count_cap_returns_enobufs failed: %d\n", rc);
    return rc;
  }
  rc = test_outbound_byte_cap_returns_enobufs();
  if (rc != 0) {
    fprintf(stderr, "test_outbound_byte_cap_returns_enobufs failed: %d\n", rc);
    return rc;
  }
  rc = test_inbound_timed_dequeue_wakes_on_packet();
  if (rc != 0) {
    fprintf(stderr, "test_inbound_timed_dequeue_wakes_on_packet failed: %d\n", rc);
    return rc;
  }
  rc = test_destroy_wakes_waiting_inbound_dequeue();
  if (rc != 0) {
    fprintf(stderr, "test_destroy_wakes_waiting_inbound_dequeue failed: %d\n", rc);
    return rc;
  }
  puts("test_packet_endpoint: ok");
  return 0;
}
