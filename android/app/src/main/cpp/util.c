/*
 * Process-wide JavaVM, cached JNI peers, VpnService.protectSocketFd for raw UDP sockets, and
 * tunnel_engine_log: Android logcat plus optional forward to Flutter
 * (VpnTunnelEvents.emitEngineLogFromNative).
 */
#include "engine.h"

#include <android/log.h>
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
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(buf, sizeof(buf), fmt, ap);
  va_end(ap);
  buf[sizeof(buf) - 1] = '\0';
  const char *effective_tag = (tag != NULL && tag[0] != '\0') ? tag : LOG_TAG;
  __android_log_print(prio, effective_tag, "%s", buf);
  tunnel_engine_forward_to_flutter(prio, effective_tag, buf);
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
  tunnel_engine_log(ANDROID_LOG_INFO, LOG_TAG, "%s", buf);
}
