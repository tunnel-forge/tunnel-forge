/*
 * Process-wide JavaVM, VpnService.protectSocketFd for raw UDP sockets, and tunnel_engine_log:
 * Android logcat plus optional forward to Flutter (VpnTunnelEvents.emitEngineLogFromNative).
 */
#include "engine.h"

#include <android/log.h>
#include <jni.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "tunnel_engine"

static JavaVM *g_vm;
static int g_socket_protection_enabled = 1;

static void tunnel_engine_forward_to_flutter(int priority, const char *tag, const char *message) {
  if (message == NULL) {
    return;
  }
  JavaVM *vm = engine_get_java_vm();
  if (vm == NULL) {
    return;
  }
  JNIEnv *env = NULL;
  int need_detach = 0;
  jint ge = (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6);
  if (ge == JNI_EDETACHED) {
    if ((*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK || env == NULL) {
      return;
    }
    need_detach = 1;
  } else if (ge != JNI_OK || env == NULL) {
    return;
  }

  jclass clazz = (*env)->FindClass(env, "com/example/tunnel_forge/VpnTunnelEvents");
  if (clazz == NULL) {
    (*env)->ExceptionClear(env);
    goto cleanup;
  }
  jmethodID mid =
      (*env)->GetStaticMethodID(env, clazz, "emitEngineLogFromNative", "(ILjava/lang/String;Ljava/lang/String;)V");
  if (mid == NULL) {
    (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, clazz);
    goto cleanup;
  }
  const char *t = (tag != NULL && tag[0] != '\0') ? tag : LOG_TAG;
  jstring jtag = (*env)->NewStringUTF(env, t);
  jstring jmsg = (*env)->NewStringUTF(env, message);
  if (jtag == NULL || jmsg == NULL) {
    if (jtag) (*env)->DeleteLocalRef(env, jtag);
    if (jmsg) (*env)->DeleteLocalRef(env, jmsg);
    (*env)->DeleteLocalRef(env, clazz);
    goto cleanup;
  }
  (*env)->CallStaticVoidMethod(env, clazz, mid, priority, jtag, jmsg);
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionClear(env);
  }
  (*env)->DeleteLocalRef(env, jmsg);
  (*env)->DeleteLocalRef(env, jtag);
  (*env)->DeleteLocalRef(env, clazz);

cleanup:
  if (need_detach) {
    (*vm)->DetachCurrentThread(vm);
  }
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

void engine_set_java_vm(JavaVM *vm) { g_vm = vm; }

JavaVM *engine_get_java_vm(void) { return g_vm; }

void engine_set_socket_protection_enabled(int enabled) { g_socket_protection_enabled = enabled ? 1 : 0; }

int util_protect_fd(int fd) {
  if (!g_socket_protection_enabled) {
    return 0;
  }
  if (fd < 0 || g_vm == NULL) {
    return -1;
  }
  JNIEnv *env = NULL;
  int need_detach = 0;
  jint ge = (*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6);
  if (ge == JNI_EDETACHED) {
    if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK || env == NULL) {
      return -1;
    }
    need_detach = 1;
  } else if (ge != JNI_OK || env == NULL) {
    return -1;
  }

  jclass clazz = (*env)->FindClass(env, "com/example/tunnel_forge/TunnelVpnService");
  if (clazz == NULL) {
    goto cleanup;
  }
  jmethodID mid = (*env)->GetStaticMethodID(env, clazz, "protectSocketFd", "(I)Z");
  if (mid == NULL) {
    (*env)->DeleteLocalRef(env, clazz);
    goto cleanup;
  }
  jboolean ok = (*env)->CallStaticBooleanMethod(env, clazz, mid, fd);
  (*env)->DeleteLocalRef(env, clazz);
  int ret = ok ? 0 : -1;
  if (ret != 0) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "util_protect_fd(%d): VpnService.protect returned false", fd);
  }
  if (need_detach) {
    (*g_vm)->DetachCurrentThread(g_vm);
  }
  return ret;

cleanup:
  if (need_detach) {
    (*g_vm)->DetachCurrentThread(g_vm);
  }
  return -1;
}

void tunnel_log(const char *fmt, ...) {
  char buf[512];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(buf, sizeof(buf), fmt, ap);
  va_end(ap);
  tunnel_engine_log(ANDROID_LOG_INFO, LOG_TAG, "%s", buf);
}
