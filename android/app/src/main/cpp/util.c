/*
 * Process-wide JavaVM, cached JNI peers, VpnService.protectSocketFd for raw UDP sockets, and
 * tunnel_engine_log: Android logcat plus optional forward to Flutter
 * (VpnTunnelEvents.emitEngineLogFromNative).
 */
#include "engine.h"

#include <android/log.h>
#include <ctype.h>
#include <jni.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "tunnel_engine"
#define VPN_TUNNEL_EVENTS_CLASS "io/github/evokelektrique/tunnelforge/VpnTunnelEvents"
#define TUNNEL_VPN_SERVICE_CLASS "io/github/evokelektrique/tunnelforge/TunnelVpnService"

static JavaVM *g_vm;
static int g_socket_protection_enabled = 1;
static jclass g_vpn_tunnel_events_class;
static jclass g_tunnel_vpn_service_class;
static jmethodID g_emit_engine_log_from_native_mid;
static jmethodID g_protect_socket_fd_mid;

typedef struct {
  const char *key;
  const char *placeholder;
} tunnel_sensitive_key_t;

static const tunnel_sensitive_key_t k_sensitive_keys[] = {
    {"password", "[REDACTED]"},      {"psk", "[REDACTED]"},         {"secret", "[REDACTED]"},
    {"token", "[REDACTED]"},         {"cookie", "[REDACTED]"},      {"authorization", "[REDACTED]"},
    {"user", "[REDACTED]"},          {"username", "[REDACTED]"},
    {"server", "[REDACTED_HOST]"},   {"host", "[REDACTED_HOST]"},   {"dns", "[REDACTED_HOST]"},
    {"uri", "[REDACTED_URI]"},       {"url", "[REDACTED_URI]"},     {"target", "[REDACTED_TARGET]"},
    {"from", "[REDACTED_TARGET]"},   {"next", "[REDACTED_HOST]"},   {"resolved", "[REDACTED_HOST]"},
    {"source", "[REDACTED_HOST]"},   {"expected", "[REDACTED_HOST]"},
    {"hostname", "[REDACTED_HOST]"}, {"ip", "[REDACTED_HOST]"},     {"clientIpv4", "[REDACTED_HOST]"},
};

static int tunnel_ascii_tolower(int ch) {
  if (ch >= 'A' && ch <= 'Z') return ch - 'A' + 'a';
  return ch;
}

static int tunnel_chars_equal_ci(char a, char b) {
  return tunnel_ascii_tolower((unsigned char)a) == tunnel_ascii_tolower((unsigned char)b);
}

static int tunnel_is_word_char(int ch) { return isalnum((unsigned char)ch) || ch == '_'; }

static int tunnel_is_value_separator(int ch) {
  return ch == '\0' || isspace((unsigned char)ch) || ch == ',' || ch == ';' || ch == ')' || ch == ']';
}

static void tunnel_append_range(char *output, size_t output_len, size_t *out_pos, const char *src, size_t len) {
  if (output_len == 0 || *out_pos >= output_len - 1) return;
  size_t room = output_len - 1 - *out_pos;
  if (len > room) len = room;
  memcpy(output + *out_pos, src, len);
  *out_pos += len;
  output[*out_pos] = '\0';
}

static void tunnel_append_cstr(char *output, size_t output_len, size_t *out_pos, const char *src) {
  tunnel_append_range(output, output_len, out_pos, src, strlen(src));
}

static int tunnel_ci_equal_n(const char *a, const char *b, size_t len) {
  for (size_t i = 0; i < len; i++) {
    if (a[i] == '\0' || b[i] == '\0' || !tunnel_chars_equal_ci(a[i], b[i])) return 0;
  }
  return 1;
}

static int tunnel_key_equals(const char *key, const char *literal) {
  size_t len = strlen(literal);
  return strlen(key) == len && tunnel_ci_equal_n(key, literal, len);
}

static int tunnel_looks_like_uri(const char *value, size_t len) {
  if (len < 4) return 0;
  for (size_t i = 0; i + 2 < len; i++) {
    if (value[i] == ':' && value[i + 1] == '/' && value[i + 2] == '/') return 1;
  }
  return 0;
}

static int tunnel_split_host_port(const char *value, size_t len, size_t *host_len, size_t *port_pos) {
  *host_len = len;
  *port_pos = len;
  for (size_t i = len; i > 0; i--) {
    if (value[i - 1] != ':') continue;
    size_t digits = len - i;
    if (digits == 0 || digits > 5) return 0;
    for (size_t j = i; j < len; j++) {
      if (!isdigit((unsigned char)value[j])) return 0;
    }
    *host_len = i - 1;
    *port_pos = i;
    return 1;
  }
  return 0;
}

static int tunnel_looks_like_ipv4_host(const char *value, size_t len) {
  if (len == 0) return 0;
  int dots = 0;
  int have_digit = 0;
  int octet = 0;
  int octet_len = 0;
  for (size_t i = 0; i < len; i++) {
    unsigned char ch = (unsigned char)value[i];
    if (isdigit(ch)) {
      have_digit = 1;
      octet = octet * 10 + (int)(ch - '0');
      octet_len++;
      if (octet_len > 3 || octet > 255) return 0;
      continue;
    }
    if (ch == '.') {
      if (!have_digit || octet_len == 0) return 0;
      dots++;
      have_digit = 0;
      octet = 0;
      octet_len = 0;
      continue;
    }
    return 0;
  }
  return dots == 3 && have_digit && octet_len > 0;
}

static int tunnel_looks_like_hostname_host(const char *value, size_t len) {
  if (len == 0) return 0;
  int saw_dot = 0;
  int saw_alpha = 0;
  size_t label_len = 0;
  for (size_t i = 0; i < len; i++) {
    unsigned char ch = (unsigned char)value[i];
    if (isalnum(ch)) {
      label_len++;
      if (isalpha(ch)) saw_alpha = 1;
      continue;
    }
    if (ch == '-') {
      if (label_len == 0) return 0;
      label_len++;
      continue;
    }
    if (ch == '.') {
      if (label_len == 0 || value[i - 1] == '-') return 0;
      saw_dot = 1;
      label_len = 0;
      continue;
    }
    return 0;
  }
  return saw_dot && saw_alpha && label_len > 0 && value[len - 1] != '-';
}

static int tunnel_looks_like_target(const char *value, size_t len) {
  size_t host_len = len;
  size_t port_pos = len;
  tunnel_split_host_port(value, len, &host_len, &port_pos);
  return tunnel_looks_like_ipv4_host(value, host_len) || tunnel_looks_like_hostname_host(value, host_len);
}

static int tunnel_looks_like_long_hex(const char *value, size_t len) {
  if (len < 16) return 0;
  for (size_t i = 0; i < len; i++) {
    if (!isxdigit((unsigned char)value[i])) return 0;
  }
  return 1;
}

static void tunnel_append_placeholder_with_port(char *output, size_t output_len, size_t *out_pos, const char *value,
                                                size_t len, const char *placeholder) {
  size_t host_len = len;
  size_t port_pos = len;
  tunnel_append_cstr(output, output_len, out_pos, placeholder);
  if (strcmp(placeholder, "[REDACTED_URI]") == 0) return;
  if (tunnel_split_host_port(value, len, &host_len, &port_pos)) {
    tunnel_append_cstr(output, output_len, out_pos, ":");
    tunnel_append_range(output, output_len, out_pos, value + port_pos, len - port_pos);
  }
}

static int tunnel_match_sensitive_key(const char *input, size_t i, size_t *prefix_end, size_t *value_end,
                                      const char **placeholder) {
  for (size_t k = 0; k < sizeof(k_sensitive_keys) / sizeof(k_sensitive_keys[0]); k++) {
    const char *key = k_sensitive_keys[k].key;
    size_t key_len = strlen(key);
    if (!tunnel_ci_equal_n(input + i, key, key_len)) continue;
    if (i > 0 && tunnel_is_word_char((unsigned char)input[i - 1])) continue;
    if (tunnel_is_word_char((unsigned char)input[i + key_len])) continue;

    size_t pos = i + key_len;
    while (input[pos] != '\0' && isspace((unsigned char)input[pos])) pos++;
    if (input[pos] != '=' && input[pos] != ':') continue;
    pos++;
    while (input[pos] != '\0' && isspace((unsigned char)input[pos])) pos++;
    *prefix_end = pos;
    *value_end = pos;

    if (tunnel_key_equals(key, "dns")) {
      while (input[*value_end] != '\0' && !isspace((unsigned char)input[*value_end]) && input[*value_end] != ';') {
        (*value_end)++;
      }
    } else {
      while (input[*value_end] != '\0' && !tunnel_is_value_separator((unsigned char)input[*value_end])) {
        (*value_end)++;
      }
    }

    if (tunnel_key_equals(key, "expected")) {
      const char *value = input + pos;
      size_t value_len = *value_end - pos;
      if (!tunnel_looks_like_uri(value, value_len) && !tunnel_looks_like_target(value, value_len)) {
        continue;
      }
    }
    *placeholder = k_sensitive_keys[k].placeholder;
    return 1;
  }
  return 0;
}

static size_t tunnel_next_token_end(const char *input, size_t i) {
  size_t pos = i;
  while (input[pos] != '\0' && !isspace((unsigned char)input[pos])) pos++;
  return pos;
}

static void tunnel_append_redacted_token(char *output, size_t output_len, size_t *out_pos, const char *token,
                                         size_t len) {
  size_t core_len = len;
  while (core_len > 0) {
    char ch = token[core_len - 1];
    if (ch == '.' || ch == ',' || ch == ';' || ch == ')' || ch == ']') {
      core_len--;
      continue;
    }
    break;
  }

  if (tunnel_looks_like_uri(token, core_len)) {
    tunnel_append_cstr(output, output_len, out_pos, "[REDACTED_URI]");
  } else if (tunnel_looks_like_target(token, core_len)) {
    tunnel_append_placeholder_with_port(output, output_len, out_pos, token, core_len, "[REDACTED_TARGET]");
  } else if (tunnel_looks_like_long_hex(token, core_len)) {
    tunnel_append_cstr(output, output_len, out_pos, "[REDACTED]");
  } else {
    tunnel_append_range(output, output_len, out_pos, token, len);
    return;
  }

  tunnel_append_range(output, output_len, out_pos, token + core_len, len - core_len);
}

static void tunnel_redact_log_message(const char *input, char *output, size_t output_len) {
  if (output_len == 0) return;
  if (input == NULL) {
    output[0] = '\0';
    return;
  }

  size_t in_pos = 0;
  size_t out_pos = 0;
  output[0] = '\0';

  while (input[in_pos] != '\0' && out_pos < output_len - 1) {
    size_t prefix_end = 0;
    size_t value_end = 0;
    const char *placeholder = NULL;
    if (tunnel_match_sensitive_key(input, in_pos, &prefix_end, &value_end, &placeholder)) {
      tunnel_append_range(output, output_len, &out_pos, input + in_pos, prefix_end - in_pos);
      tunnel_append_placeholder_with_port(output, output_len, &out_pos, input + prefix_end, value_end - prefix_end,
                                          placeholder);
      in_pos = value_end;
      continue;
    }

    if (isspace((unsigned char)input[in_pos])) {
      tunnel_append_range(output, output_len, &out_pos, input + in_pos, 1);
      in_pos++;
      continue;
    }

    size_t token_end = tunnel_next_token_end(input, in_pos);
    tunnel_append_redacted_token(output, output_len, &out_pos, input + in_pos, token_end - in_pos);
    in_pos = token_end;
  }

  output[out_pos] = '\0';
}

static void engine_log_direct(int priority, const char *message) {
  __android_log_print(priority, LOG_TAG, "%s", message);
}

static JNIEnv *engine_require_env(JavaVM *vm, int *need_detach) {
  if (need_detach != NULL) {
    *need_detach = 0;
  }
  if (vm == NULL) {
    return NULL;
  }
  JNIEnv *env = NULL;
  jint ge = (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6);
  if (ge == JNI_EDETACHED) {
    if ((*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK || env == NULL) {
      return NULL;
    }
    if (need_detach != NULL) {
      *need_detach = 1;
    }
    return env;
  }
  if (ge != JNI_OK || env == NULL) {
    return NULL;
  }
  return env;
}

static void engine_release_env(JavaVM *vm, int need_detach) {
  if (need_detach && vm != NULL) {
    (*vm)->DetachCurrentThread(vm);
  }
}

void engine_set_java_vm(JavaVM *vm) { g_vm = vm; }

JavaVM *engine_get_java_vm(void) { return g_vm; }

int engine_jni_init(JNIEnv *env) {
  jclass tunnel_events_local = NULL;
  jclass vpn_service_local = NULL;

  g_vpn_tunnel_events_class = NULL;
  g_tunnel_vpn_service_class = NULL;
  g_emit_engine_log_from_native_mid = NULL;
  g_protect_socket_fd_mid = NULL;

  tunnel_events_local = (*env)->FindClass(env, VPN_TUNNEL_EVENTS_CLASS);
  if (tunnel_events_local == NULL) {
    (*env)->ExceptionClear(env);
    engine_log_direct(ANDROID_LOG_ERROR, "engine_jni_init: could not find VpnTunnelEvents");
    goto fail;
  }
  g_vpn_tunnel_events_class = (jclass)(*env)->NewGlobalRef(env, tunnel_events_local);
  (*env)->DeleteLocalRef(env, tunnel_events_local);
  tunnel_events_local = NULL;
  if (g_vpn_tunnel_events_class == NULL) {
    engine_log_direct(ANDROID_LOG_ERROR, "engine_jni_init: could not cache VpnTunnelEvents");
    goto fail;
  }
  g_emit_engine_log_from_native_mid =
      (*env)->GetStaticMethodID(env, g_vpn_tunnel_events_class, "emitEngineLogFromNative",
                                "(ILjava/lang/String;Ljava/lang/String;)V");
  if (g_emit_engine_log_from_native_mid == NULL) {
    (*env)->ExceptionClear(env);
    engine_log_direct(ANDROID_LOG_ERROR, "engine_jni_init: could not find emitEngineLogFromNative");
    goto fail;
  }

  vpn_service_local = (*env)->FindClass(env, TUNNEL_VPN_SERVICE_CLASS);
  if (vpn_service_local == NULL) {
    (*env)->ExceptionClear(env);
    engine_log_direct(ANDROID_LOG_ERROR, "engine_jni_init: could not find TunnelVpnService");
    goto fail;
  }
  g_tunnel_vpn_service_class = (jclass)(*env)->NewGlobalRef(env, vpn_service_local);
  (*env)->DeleteLocalRef(env, vpn_service_local);
  vpn_service_local = NULL;
  if (g_tunnel_vpn_service_class == NULL) {
    engine_log_direct(ANDROID_LOG_ERROR, "engine_jni_init: could not cache TunnelVpnService");
    goto fail;
  }
  g_protect_socket_fd_mid = (*env)->GetStaticMethodID(env, g_tunnel_vpn_service_class, "protectSocketFd", "(I)Z");
  if (g_protect_socket_fd_mid == NULL) {
    (*env)->ExceptionClear(env);
    engine_log_direct(ANDROID_LOG_ERROR, "engine_jni_init: could not find protectSocketFd");
    goto fail;
  }

  return 0;

fail:
  if (tunnel_events_local != NULL) {
    (*env)->DeleteLocalRef(env, tunnel_events_local);
  }
  if (vpn_service_local != NULL) {
    (*env)->DeleteLocalRef(env, vpn_service_local);
  }
  engine_jni_cleanup(env);
  return -1;
}

void engine_jni_cleanup(JNIEnv *env) {
  g_emit_engine_log_from_native_mid = NULL;
  g_protect_socket_fd_mid = NULL;
  if (g_vpn_tunnel_events_class != NULL) {
    (*env)->DeleteGlobalRef(env, g_vpn_tunnel_events_class);
    g_vpn_tunnel_events_class = NULL;
  }
  if (g_tunnel_vpn_service_class != NULL) {
    (*env)->DeleteGlobalRef(env, g_tunnel_vpn_service_class);
    g_tunnel_vpn_service_class = NULL;
  }
}

static void tunnel_engine_forward_to_flutter(int priority, const char *tag, const char *message) {
  if (message == NULL || g_vpn_tunnel_events_class == NULL || g_emit_engine_log_from_native_mid == NULL) {
    return;
  }
  JavaVM *vm = engine_get_java_vm();
  if (vm == NULL) {
    return;
  }
  int need_detach = 0;
  JNIEnv *env = engine_require_env(vm, &need_detach);
  if (env == NULL) {
    return;
  }

  const char *effective_tag = (tag != NULL && tag[0] != '\0') ? tag : LOG_TAG;
  jstring jtag = (*env)->NewStringUTF(env, effective_tag);
  jstring jmsg = (*env)->NewStringUTF(env, message);
  if (jtag == NULL || jmsg == NULL) {
    if (jtag != NULL) {
      (*env)->DeleteLocalRef(env, jtag);
    }
    if (jmsg != NULL) {
      (*env)->DeleteLocalRef(env, jmsg);
    }
    if ((*env)->ExceptionCheck(env)) {
      (*env)->ExceptionClear(env);
    }
    engine_release_env(vm, need_detach);
    return;
  }
  (*env)->CallStaticVoidMethod(env, g_vpn_tunnel_events_class, g_emit_engine_log_from_native_mid, priority, jtag, jmsg);
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionClear(env);
  }
  (*env)->DeleteLocalRef(env, jmsg);
  (*env)->DeleteLocalRef(env, jtag);
  engine_release_env(vm, need_detach);
}

void tunnel_engine_log(int prio, const char *tag, const char *fmt, ...) {
  char buf[2048];
  char redacted[2048];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(buf, sizeof(buf), fmt, ap);
  va_end(ap);
  buf[sizeof(buf) - 1] = '\0';
  tunnel_redact_log_message(buf, redacted, sizeof(redacted));
  const char *effective_tag = (tag != NULL && tag[0] != '\0') ? tag : LOG_TAG;
  __android_log_print(prio, effective_tag, "%s", redacted);
  tunnel_engine_forward_to_flutter(prio, effective_tag, redacted);
}

void engine_set_socket_protection_enabled(int enabled) { g_socket_protection_enabled = enabled ? 1 : 0; }

int util_protect_fd(int fd) {
  if (!g_socket_protection_enabled) {
    return 0;
  }
  if (fd < 0 || g_vm == NULL || g_tunnel_vpn_service_class == NULL || g_protect_socket_fd_mid == NULL) {
    return -1;
  }
  int need_detach = 0;
  JNIEnv *env = engine_require_env(g_vm, &need_detach);
  if (env == NULL) {
    return -1;
  }

  jboolean ok = (*env)->CallStaticBooleanMethod(env, g_tunnel_vpn_service_class, g_protect_socket_fd_mid, fd);
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionClear(env);
    engine_release_env(g_vm, need_detach);
    return -1;
  }
  int ret = ok ? 0 : -1;
  if (ret != 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "util_protect_fd(%d): VpnService.protect returned false", fd);
  }
  engine_release_env(g_vm, need_detach);
  return ret;
}

void tunnel_log(const char *fmt, ...) {
  char buf[512];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(buf, sizeof(buf), fmt, ap);
  va_end(ap);
  tunnel_engine_log(ANDROID_LOG_DEBUG, LOG_TAG, "%s", buf);
}
