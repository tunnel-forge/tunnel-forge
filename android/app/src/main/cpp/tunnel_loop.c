/*
 * Orchestrates IKE+ESP, L2TP, and PPP (tunnel_negotiate), then polls TUN and the ESP socket
 * (tunnel_run_loop). g_state holds the negotiated session between phases; g_stop is set from
 * JNI via tunnel_loop_stop for cooperative shutdown.
 */
#include "engine.h"

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#include "esp_udp.h"
#include "ikev1.h"
#include "l2tp.h"
#include "ppp.h"

#define LOG_TAG "tunnel_engine"

static atomic_int g_stop;

static struct {
  ike_session_t ike;
  esp_keys_t esp;
  l2tp_session_t l2tp;
  ppp_session_t ppp;
  int ready;
} g_state;

void tunnel_loop_stop(void) { atomic_store(&g_stop, 1); }

static int set_nonblock(int fd) {
  int flags = fcntl(fd, F_GETFL, 0);
  if (flags < 0) return -1;
  return fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

int tunnel_negotiate(const char *server, const char *user, const char *password, const char *psk) {
  g_state.ready = 0;
  tunnel_log("tunnel negotiate server=%s", server);

  memset(&g_state.ike, 0, sizeof(g_state.ike));
  memset(&g_state.esp, 0, sizeof(g_state.esp));

  if (ikev1_connect(server, psk, &g_state.ike, &g_state.esp) != 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG,
                        "ikev1_connect failed (IPsec/IKE). Filter logcat: adb logcat -s tunnel_engine:V");
    return TUNNEL_EXIT_IKE_FAILED;
  }

  memset(&g_state.l2tp, 0, sizeof(g_state.l2tp));
  if (l2tp_handshake(g_state.ike.esp_fd, &g_state.esp,
                     (struct sockaddr *)&g_state.ike.peer, g_state.ike.peer_len,
                     &g_state.l2tp) != 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "L2TP handshake failed");
    close(g_state.ike.esp_fd);
    return TUNNEL_EXIT_L2TP_FAILED;
  }

  memset(&g_state.ppp, 0, sizeof(g_state.ppp));
  if (ppp_negotiate(g_state.ike.esp_fd, &g_state.esp,
                    (struct sockaddr *)&g_state.ike.peer, g_state.ike.peer_len,
                    &g_state.l2tp, user, password, &g_state.ppp) != 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "PPP negotiation failed");
    close(g_state.ike.esp_fd);
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

int tunnel_run_loop(int tun_fd) {
  if (!g_state.ready) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "tunnel_run_loop: not negotiated");
    return TUNNEL_EXIT_BAD_ARGS;
  }
  atomic_store(&g_stop, 0);

  if (set_nonblock(tun_fd) != 0 || set_nonblock(g_state.ike.esp_fd) != 0) {
    tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "nonblock failed: errno=%d", errno);
  }

  tunnel_engine_log(ANDROID_LOG_INFO, LOG_TAG, "tunnel poll loop: tun_fd=%d esp_fd=%d udp_encap=%d", tun_fd,
                    g_state.ike.esp_fd, g_state.esp.udp_encap ? 1 : 0);

  uint8_t tun_buf[65536];
  uint8_t esp_buf[65536];
  time_t last_nat_keep = time(NULL);

  while (!atomic_load(&g_stop)) {
    struct pollfd fds[2];
    fds[0].fd = tun_fd;
    fds[0].events = POLLIN;
    fds[1].fd = g_state.ike.esp_fd;
    fds[1].events = POLLIN;
    int pr = poll(fds, 2, 500);
    if (pr < 0) {
      if (errno == EINTR) continue;
      tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "poll failed errno=%d, closing ESP fd", errno);
      close(g_state.ike.esp_fd);
      g_state.ready = 0;
      return TUNNEL_EXIT_POLL_ERROR;
    }
    if (pr == 0) {
      engine_dp_maybe_log_summary(time(NULL));
      if (g_state.esp.udp_encap && g_state.esp.enc_key_len) {
        time_t now = time(NULL);
        if (now - last_nat_keep >= 25) {
          uint8_t z[4] = {0};
          (void)send(g_state.ike.esp_fd, z, sizeof(z), 0);
          last_nat_keep = now;
        }
      }
      continue;
    }

    if (fds[0].revents & POLLIN) {
      ssize_t n = read(tun_fd, tun_buf, sizeof(tun_buf));
      if (n > 0) {
        engine_dp_note_tun_rx();
        if (ppp_encapsulate_and_send(g_state.ike.esp_fd, &g_state.esp,
                                     (struct sockaddr *)&g_state.ike.peer,
                                     g_state.ike.peer_len, &g_state.l2tp,
                                     &g_state.ppp, tun_buf, (size_t)n) < 0) {
          engine_dp_note_encap_fail();
          tunnel_engine_log(ANDROID_LOG_WARN, LOG_TAG, "egress failed");
        } else {
          engine_dp_note_encap_ok();
          last_nat_keep = time(NULL);
        }
      }
    }

    if (fds[1].revents & POLLIN) {
      ssize_t n = recv(g_state.ike.esp_fd, esp_buf, sizeof(esp_buf), 0);
      if (n > 0) {
        engine_dp_note_esp_rx();
        last_nat_keep = time(NULL);
        // RFC 3948 NAT-T keepalive: 4 zero bytes; not ESP - do not feed decrypt/parser.
        if (g_state.esp.udp_encap && (size_t)n == 4u && util_read_be32(esp_buf) == ESP_UDP_NON_ESP_MARKER) {
          engine_dp_maybe_log_summary(time(NULL));
          continue;
        }
        uint8_t plain[65536];
        size_t plain_len = sizeof(plain);
        if (esp_try_decrypt(&g_state.esp, esp_buf, (size_t)n, plain, &plain_len) == 0 && plain_len > 0) {
          engine_dp_note_esp_plain_ok();
          l2tp_dispatch_incoming(g_state.ike.esp_fd, &g_state.esp, (struct sockaddr *)&g_state.ike.peer,
                                 g_state.ike.peer_len, &g_state.l2tp, plain, plain_len, tun_fd, &g_state.ppp);
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

  close(g_state.ike.esp_fd);
  g_state.ready = 0;
  tunnel_log("tunnel stopped");
  return TUNNEL_EXIT_OK;
}

int tunnel_loop_run(int tun_fd, const char *server, const char *user, const char *password, const char *psk) {
  atomic_store(&g_stop, 0);
  tunnel_log("tunnel start server=%s", server);
  int rc = tunnel_negotiate(server, user, password, psk);
  if (rc != TUNNEL_EXIT_OK) return rc;
  return tunnel_run_loop(tun_fd);
}

/* --- Dataplane counters (tunnel_run_loop + ppp_dispatch) --- */

static uint64_t g_dp_tun_rx;
static uint64_t g_dp_encap_ok;
static uint64_t g_dp_encap_fail;
static uint64_t g_dp_esp_rx;
static uint64_t g_dp_esp_plain_ok;
static uint64_t g_dp_esp_plain_fail;
static uint64_t g_dp_tun_ipv4_wr;

void engine_dp_note_tun_rx(void) { g_dp_tun_rx++; }

void engine_dp_note_encap_ok(void) { g_dp_encap_ok++; }

void engine_dp_note_encap_fail(void) { g_dp_encap_fail++; }

void engine_dp_note_esp_rx(void) { g_dp_esp_rx++; }

void engine_dp_note_esp_plain_ok(void) { g_dp_esp_plain_ok++; }

void engine_dp_note_esp_plain_fail(void) { g_dp_esp_plain_fail++; }

void engine_dp_note_tun_ipv4_written(size_t nbytes) {
  (void)nbytes;
  g_dp_tun_ipv4_wr++;
}

void engine_dp_maybe_log_summary(time_t now) {
  static time_t s_last;
  if (s_last == 0) s_last = now;
  if (now - s_last < 30) return;
  s_last = now;
  tunnel_engine_log(
      ANDROID_LOG_INFO, LOG_TAG,
      "dataplane 30s tun_rx=%llu encap_ok=%llu encap_fail=%llu esp_rx=%llu esp_plain_ok=%llu esp_plain_fail=%llu tun_ipv4_wr=%llu",
      (unsigned long long)g_dp_tun_rx, (unsigned long long)g_dp_encap_ok, (unsigned long long)g_dp_encap_fail,
      (unsigned long long)g_dp_esp_rx, (unsigned long long)g_dp_esp_plain_ok,
      (unsigned long long)g_dp_esp_plain_fail, (unsigned long long)g_dp_tun_ipv4_wr);
}
