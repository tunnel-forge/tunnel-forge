/*
 * JNI surface for Kotlin VpnBridge: registers JavaVM, forwards tunnel run / negotiate / stop
 * to tunnel_loop.c. Every jstring is converted with GetStringUTFChars and released on all paths.
 */
#include "engine.h"

#include <android/log.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "tunnel_engine"

void tunnel_loop_stop(void);

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  (void)reserved;
  engine_set_java_vm(vm);
  return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL Java_com_example_tunnel_1forge_VpnBridge_nativeRunTunnel(JNIEnv *env, jclass clazz, jint tun_fd,
                                                                                jstring jserver, jstring juser,
                                                                                jstring jpassword, jstring jpsk,
                                                                                jint tun_mtu) {
  (void)clazz;
  jint out = (jint)TUNNEL_EXIT_BAD_ARGS;
  const char *server = (*env)->GetStringUTFChars(env, jserver, NULL);
  const char *user = (*env)->GetStringUTFChars(env, juser, NULL);
  const char *password = (*env)->GetStringUTFChars(env, jpassword, NULL);
  const char *psk = (*env)->GetStringUTFChars(env, jpsk, NULL);
  if (server == NULL || user == NULL || password == NULL || psk == NULL) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "nativeRunTunnel: null string");
    goto cleanup;
  }
  out = (jint)tunnel_loop_run(tun_fd, server, user, password, psk, (int)tun_mtu);
cleanup:
  if (server) (*env)->ReleaseStringUTFChars(env, jserver, server);
  if (user) (*env)->ReleaseStringUTFChars(env, juser, user);
  if (password) (*env)->ReleaseStringUTFChars(env, jpassword, password);
  if (psk) (*env)->ReleaseStringUTFChars(env, jpsk, psk);
  return out;
}

JNIEXPORT jint JNICALL Java_com_example_tunnel_1forge_VpnBridge_nativeNegotiate(
    JNIEnv *env, jclass clazz, jstring jserver, jstring juser, jstring jpassword, jstring jpsk,
    jint tun_mtu, jintArray jout_client_ipv4) {
  (void)clazz;
  jint out = (jint)TUNNEL_EXIT_BAD_ARGS;
  const char *server = (*env)->GetStringUTFChars(env, jserver, NULL);
  const char *user = (*env)->GetStringUTFChars(env, juser, NULL);
  const char *password = (*env)->GetStringUTFChars(env, jpassword, NULL);
  const char *psk = (*env)->GetStringUTFChars(env, jpsk, NULL);
  if (server == NULL || user == NULL || password == NULL || psk == NULL) {
    tunnel_engine_log(ANDROID_LOG_ERROR, LOG_TAG, "nativeNegotiate: null string");
    goto cleanup;
  }
  out = (jint)tunnel_negotiate(server, user, password, psk, (int)tun_mtu);
  if (out == (jint)TUNNEL_EXIT_OK && jout_client_ipv4 != NULL) {
    jsize alen = (*env)->GetArrayLength(env, jout_client_ipv4);
    if (alen >= 4) {
      uint8_t b[4];
      tunnel_negotiated_client_ipv4(b);
      jint tmp[4] = {(jint)b[0], (jint)b[1], (jint)b[2], (jint)b[3]};
      (*env)->SetIntArrayRegion(env, jout_client_ipv4, 0, 4, tmp);
    }
  }
cleanup:
  if (server) (*env)->ReleaseStringUTFChars(env, jserver, server);
  if (user) (*env)->ReleaseStringUTFChars(env, juser, user);
  if (password) (*env)->ReleaseStringUTFChars(env, jpassword, password);
  if (psk) (*env)->ReleaseStringUTFChars(env, jpsk, psk);
  return out;
}

JNIEXPORT void JNICALL Java_com_example_tunnel_1forge_VpnBridge_nativeSetSocketProtectionEnabled(JNIEnv *env,
                                                                                                  jclass clazz,
                                                                                                  jboolean enabled) {
  (void)env;
  (void)clazz;
  engine_set_socket_protection_enabled(enabled ? 1 : 0);
}

JNIEXPORT jint JNICALL Java_com_example_tunnel_1forge_VpnBridge_nativeStartLoop(JNIEnv *env, jclass clazz,
                                                                                 jint tun_fd) {
  (void)env;
  (void)clazz;
  return (jint)tunnel_run_loop(tun_fd);
}

JNIEXPORT jint JNICALL Java_com_example_tunnel_1forge_VpnBridge_nativeStartProxyLoop(JNIEnv *env, jclass clazz) {
  (void)env;
  (void)clazz;
  return (jint)tunnel_run_proxy_loop();
}

JNIEXPORT jboolean JNICALL Java_com_example_tunnel_1forge_VpnBridge_nativeIsProxyPacketBridgeActive(JNIEnv *env,
                                                                                                     jclass clazz) {
  (void)env;
  (void)clazz;
  return tunnel_proxy_is_bridge_active() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_com_example_tunnel_1forge_VpnBridge_nativeQueueProxyOutboundPacket(JNIEnv *env,
                                                                                                jclass clazz,
                                                                                                jbyteArray jpacket) {
  (void)clazz;
  if (jpacket == NULL) return (jint)TUNNEL_EXIT_BAD_ARGS;
  jsize len = (*env)->GetArrayLength(env, jpacket);
  if (len <= 0) return (jint)TUNNEL_EXIT_BAD_ARGS;
  jbyte *bytes = (*env)->GetByteArrayElements(env, jpacket, NULL);
  if (bytes == NULL) return (jint)TUNNEL_EXIT_BAD_ARGS;
  int rc = tunnel_proxy_enqueue_outbound_packet((const uint8_t *)bytes, (size_t)len);
  (*env)->ReleaseByteArrayElements(env, jpacket, bytes, JNI_ABORT);
  return (jint)rc;
}

JNIEXPORT jbyteArray JNICALL Java_com_example_tunnel_1forge_VpnBridge_nativeReadProxyInboundPacket(JNIEnv *env,
                                                                                                    jclass clazz,
                                                                                                    jint max_len) {
  (void)clazz;
  if (max_len <= 0) return NULL;
  uint8_t *buf = (uint8_t *)malloc((size_t)max_len);
  if (buf == NULL) return NULL;
  ssize_t n = tunnel_proxy_dequeue_inbound_packet(buf, (size_t)max_len);
  if (n <= 0) {
    free(buf);
    return NULL;
  }
  jbyteArray out = (*env)->NewByteArray(env, (jsize)n);
  if (out != NULL) {
    (*env)->SetByteArrayRegion(env, out, 0, (jsize)n, (const jbyte *)buf);
  }
  free(buf);
  return out;
}

JNIEXPORT void JNICALL Java_com_example_tunnel_1forge_VpnBridge_nativeStopTunnel(JNIEnv *env, jclass clazz) {
  (void)env;
  (void)clazz;
  tunnel_loop_stop();
}
