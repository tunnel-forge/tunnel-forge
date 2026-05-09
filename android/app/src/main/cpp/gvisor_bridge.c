#include "gvisor_bridge.h"

#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <stdint.h>
#include <string.h>

#define LOG_TAG "gvisor_bridge"

typedef int (*tf_gvisor_start_fn)(const uint8_t *client_ipv4, int mtu);
typedef void (*tf_gvisor_stop_fn)(void);
typedef int (*tf_gvisor_inject_inbound_fn)(const uint8_t *packet, int len);
typedef int (*tf_gvisor_read_outbound_fn)(uint8_t *packet, int len, int timeout_ms);
typedef int (*tf_gvisor_tcp_open_fn)(const uint8_t *remote_ipv4, int port, int timeout_ms);
typedef int (*tf_gvisor_tcp_open_cancelable_fn)(int open_id, const uint8_t *remote_ipv4, int port, int timeout_ms);
typedef int (*tf_gvisor_tcp_cancel_open_fn)(int open_id);
typedef int (*tf_gvisor_tcp_read_fn)(int session_id, uint8_t *buf, int len, int timeout_ms);
typedef int (*tf_gvisor_tcp_write_fn)(int session_id, const uint8_t *buf, int len, int timeout_ms);
typedef void (*tf_gvisor_tcp_close_fn)(int session_id);
typedef int (*tf_gvisor_stats_fn)(int *out, int count);
typedef int (*tf_gvisor_last_open_diagnostics_fn)(char *out, int count);
typedef int (*tf_gvisor_open_diagnostics_fn)(int open_id, char *out, int count);

static struct {
  void *handle;
  tf_gvisor_start_fn start;
  tf_gvisor_stop_fn stop;
  tf_gvisor_inject_inbound_fn inject_inbound;
  tf_gvisor_read_outbound_fn read_outbound;
  tf_gvisor_tcp_open_fn tcp_open;
  tf_gvisor_tcp_open_cancelable_fn tcp_open_cancelable;
  tf_gvisor_tcp_cancel_open_fn tcp_cancel_open;
  tf_gvisor_tcp_read_fn tcp_read;
  tf_gvisor_tcp_write_fn tcp_write;
  tf_gvisor_tcp_close_fn tcp_close;
  tf_gvisor_stats_fn stats;
  tf_gvisor_last_open_diagnostics_fn last_open_diagnostics;
  tf_gvisor_open_diagnostics_fn open_diagnostics;
} g_gvisor;

static int load_symbol(void **target, const char *name) {
  *target = dlsym(g_gvisor.handle, name);
  if (*target == NULL) {
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "missing symbol %s: %s", name, dlerror());
    errno = ENOSYS;
    return -1;
  }
  return 0;
}

static int ensure_loaded(void) {
  if (g_gvisor.handle != NULL)
    return 0;
  g_gvisor.handle = dlopen("libtunnel_gvisor.so", RTLD_NOW | RTLD_LOCAL);
  if (g_gvisor.handle == NULL) {
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "dlopen libtunnel_gvisor.so failed: %s", dlerror());
    errno = ENOSYS;
    return -1;
  }
  if (load_symbol((void **)&g_gvisor.start, "tf_gvisor_start") != 0 ||
      load_symbol((void **)&g_gvisor.stop, "tf_gvisor_stop") != 0 ||
      load_symbol((void **)&g_gvisor.inject_inbound, "tf_gvisor_inject_inbound") != 0 ||
      load_symbol((void **)&g_gvisor.read_outbound, "tf_gvisor_read_outbound") != 0 ||
      load_symbol((void **)&g_gvisor.tcp_open, "tf_gvisor_tcp_open") != 0 ||
      load_symbol((void **)&g_gvisor.tcp_open_cancelable, "tf_gvisor_tcp_open_cancelable") != 0 ||
      load_symbol((void **)&g_gvisor.tcp_cancel_open, "tf_gvisor_tcp_cancel_open") != 0 ||
      load_symbol((void **)&g_gvisor.tcp_read, "tf_gvisor_tcp_read") != 0 ||
      load_symbol((void **)&g_gvisor.tcp_write, "tf_gvisor_tcp_write") != 0 ||
      load_symbol((void **)&g_gvisor.tcp_close, "tf_gvisor_tcp_close") != 0 ||
      load_symbol((void **)&g_gvisor.stats, "tf_gvisor_stats") != 0 ||
      load_symbol((void **)&g_gvisor.last_open_diagnostics, "tf_gvisor_last_open_diagnostics") != 0 ||
      load_symbol((void **)&g_gvisor.open_diagnostics, "tf_gvisor_open_diagnostics") != 0) {
    dlclose(g_gvisor.handle);
    memset(&g_gvisor, 0, sizeof(g_gvisor));
    errno = ENOSYS;
    return -1;
  }
  return 0;
}

int gvisor_bridge_start(const uint8_t client_ipv4[4], int mtu) {
  if (ensure_loaded() != 0)
    return -1;
  return g_gvisor.start(client_ipv4, mtu);
}

void gvisor_bridge_stop(void) {
  if (ensure_loaded() == 0)
    g_gvisor.stop();
}

int gvisor_bridge_inject_inbound(const uint8_t *packet, size_t len) {
  if (ensure_loaded() != 0 || packet == NULL || len > INT32_MAX)
    return -1;
  return g_gvisor.inject_inbound(packet, (int)len);
}

ssize_t gvisor_bridge_read_outbound(uint8_t *packet, size_t len, int timeout_ms) {
  if (ensure_loaded() != 0 || packet == NULL || len > INT32_MAX)
    return -1;
  return (ssize_t)g_gvisor.read_outbound(packet, (int)len, timeout_ms);
}

int gvisor_bridge_tcp_open(const uint8_t remote_ipv4[4], uint16_t port, int timeout_ms) {
  if (ensure_loaded() != 0 || remote_ipv4 == NULL)
    return -1;
  return g_gvisor.tcp_open(remote_ipv4, (int)port, timeout_ms);
}

int gvisor_bridge_tcp_open_cancelable(int open_id, const uint8_t remote_ipv4[4], uint16_t port, int timeout_ms) {
  if (ensure_loaded() != 0 || remote_ipv4 == NULL || open_id <= 0)
    return -1;
  return g_gvisor.tcp_open_cancelable(open_id, remote_ipv4, (int)port, timeout_ms);
}

int gvisor_bridge_tcp_cancel_open(int open_id) {
  if (ensure_loaded() != 0 || open_id <= 0)
    return -1;
  return g_gvisor.tcp_cancel_open(open_id);
}

ssize_t gvisor_bridge_tcp_read(int session_id, uint8_t *buf, size_t len, int timeout_ms) {
  if (ensure_loaded() != 0 || buf == NULL || len > INT32_MAX)
    return -1;
  return (ssize_t)g_gvisor.tcp_read(session_id, buf, (int)len, timeout_ms);
}

ssize_t gvisor_bridge_tcp_write(int session_id, const uint8_t *buf, size_t len, int timeout_ms) {
  if (ensure_loaded() != 0 || buf == NULL || len > INT32_MAX)
    return -1;
  return (ssize_t)g_gvisor.tcp_write(session_id, buf, (int)len, timeout_ms);
}

void gvisor_bridge_tcp_close(int session_id) {
  if (ensure_loaded() == 0)
    g_gvisor.tcp_close(session_id);
}

int gvisor_bridge_stats(int *out, int count) {
  if (ensure_loaded() != 0 || out == NULL)
    return -1;
  return g_gvisor.stats(out, count);
}

int gvisor_bridge_last_open_diagnostics(char *out, size_t count) {
  if (ensure_loaded() != 0 || out == NULL || count == 0 || count > INT32_MAX)
    return -1;
  return g_gvisor.last_open_diagnostics(out, (int)count);
}

int gvisor_bridge_open_diagnostics(int open_id, char *out, size_t count) {
  if (ensure_loaded() != 0 || out == NULL || count == 0 || count > INT32_MAX || open_id <= 0)
    return -1;
  return g_gvisor.open_diagnostics(open_id, out, (int)count);
}
