/*
 * JNI surface for Kotlin VpnBridge: explicit registration avoids package-coupled
 * Java_* symbol names. Every jstring is converted with GetStringUTFChars and released on all paths.
 */
#include "engine.h"

#include <android/log.h>
#include <stdlib.h>

#define LOG_TAG "tunnel_engine"
#define VPN_BRIDGE_CLASS "io/github/evokelektrique/tunnelforge/VpnBridge"

void tunnel_loop_stop(void);

static jint native_run_tunnel(JNIEnv *env, jclass clazz, jint tun_fd, jstring jserver, jstring juser, jstring jpassword,
                              jstring jpsk, jint tun_mtu) {
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
  if (server != NULL)
    (*env)->ReleaseStringUTFChars(env, jserver, server);
  if (user != NULL)
    (*env)->ReleaseStringUTFChars(env, juser, user);
  if (password != NULL)
    (*env)->ReleaseStringUTFChars(env, jpassword, password);
  if (psk != NULL)
    (*env)->ReleaseStringUTFChars(env, jpsk, psk);
  return out;
}

static jint native_negotiate(JNIEnv *env, jclass clazz, jstring jserver, jstring juser, jstring jpassword, jstring jpsk,
                             jint tun_mtu, jintArray jout_client_ipv4, jintArray jout_primary_dns_ipv4,
                             jintArray jout_secondary_dns_ipv4) {
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
      jint tmp[4];
      tunnel_negotiated_client_ipv4(b);
      tmp[0] = (jint)b[0];
      tmp[1] = (jint)b[1];
      tmp[2] = (jint)b[2];
      tmp[3] = (jint)b[3];
      (*env)->SetIntArrayRegion(env, jout_client_ipv4, 0, 4, tmp);
    }
  }
  if (out == (jint)TUNNEL_EXIT_OK) {
    uint8_t primary_dns[4];
    uint8_t secondary_dns[4];
    jint tmp[4];
    tunnel_negotiated_dns_ipv4(primary_dns, secondary_dns);
    if (jout_primary_dns_ipv4 != NULL && (*env)->GetArrayLength(env, jout_primary_dns_ipv4) >= 4) {
      tmp[0] = (jint)primary_dns[0];
      tmp[1] = (jint)primary_dns[1];
      tmp[2] = (jint)primary_dns[2];
      tmp[3] = (jint)primary_dns[3];
      (*env)->SetIntArrayRegion(env, jout_primary_dns_ipv4, 0, 4, tmp);
    }
    if (jout_secondary_dns_ipv4 != NULL && (*env)->GetArrayLength(env, jout_secondary_dns_ipv4) >= 4) {
      tmp[0] = (jint)secondary_dns[0];
      tmp[1] = (jint)secondary_dns[1];
      tmp[2] = (jint)secondary_dns[2];
      tmp[3] = (jint)secondary_dns[3];
      (*env)->SetIntArrayRegion(env, jout_secondary_dns_ipv4, 0, 4, tmp);
    }
  }

cleanup:
  if (server != NULL)
    (*env)->ReleaseStringUTFChars(env, jserver, server);
  if (user != NULL)
    (*env)->ReleaseStringUTFChars(env, juser, user);
  if (password != NULL)
    (*env)->ReleaseStringUTFChars(env, jpassword, password);
  if (psk != NULL)
    (*env)->ReleaseStringUTFChars(env, jpsk, psk);
  return out;
}

static void native_set_socket_protection_enabled(JNIEnv *env, jclass clazz, jboolean enabled) {
  (void)env;
  (void)clazz;
  engine_set_socket_protection_enabled(enabled ? 1 : 0);
}

static jint native_start_loop(JNIEnv *env, jclass clazz, jint tun_fd) {
  (void)env;
  (void)clazz;
  return (jint)tunnel_run_loop(tun_fd);
}

static jint native_start_proxy_loop(JNIEnv *env, jclass clazz) {
  (void)env;
  (void)clazz;
  return (jint)tunnel_run_proxy_loop();
}

static jboolean native_is_proxy_packet_bridge_active(JNIEnv *env, jclass clazz) {
  (void)env;
  (void)clazz;
  return tunnel_proxy_is_bridge_active() ? JNI_TRUE : JNI_FALSE;
}

static jint native_queue_proxy_outbound_packet(JNIEnv *env, jclass clazz, jbyteArray jpacket) {
  (void)clazz;
  if (jpacket == NULL)
    return (jint)TUNNEL_EXIT_BAD_ARGS;
  jsize len = (*env)->GetArrayLength(env, jpacket);
  if (len <= 0)
    return (jint)TUNNEL_EXIT_BAD_ARGS;
  jbyte *bytes = (*env)->GetByteArrayElements(env, jpacket, NULL);
  if (bytes == NULL)
    return (jint)TUNNEL_EXIT_BAD_ARGS;
  int rc = tunnel_proxy_enqueue_outbound_packet((const uint8_t *)bytes, (size_t)len);
  (*env)->ReleaseByteArrayElements(env, jpacket, bytes, JNI_ABORT);
  return (jint)rc;
}

static jbyteArray native_read_proxy_inbound_packet(JNIEnv *env, jclass clazz, jint max_len) {
  (void)clazz;
  if (max_len <= 0)
    return NULL;
  uint8_t *buf = (uint8_t *)malloc((size_t)max_len);
  if (buf == NULL)
    return NULL;
  ssize_t n = tunnel_proxy_dequeue_inbound_packet_wait(buf, (size_t)max_len, 50);
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

static jint native_set_vpn_dns_intercept_ipv4(JNIEnv *env, jclass clazz, jstring jipv4) {
  (void)clazz;
  if (jipv4 == NULL)
    return (jint)tunnel_vpn_dns_set_intercept_ipv4(NULL);
  const char *ipv4 = (*env)->GetStringUTFChars(env, jipv4, NULL);
  if (ipv4 == NULL)
    return (jint)TUNNEL_EXIT_BAD_ARGS;
  int rc = tunnel_vpn_dns_set_intercept_ipv4(ipv4);
  (*env)->ReleaseStringUTFChars(env, jipv4, ipv4);
  return (jint)rc;
}

static jbyteArray native_read_vpn_dns_query(JNIEnv *env, jclass clazz, jint max_len) {
  (void)clazz;
  if (max_len <= 0)
    return NULL;
  uint8_t *buf = (uint8_t *)malloc((size_t)max_len);
  if (buf == NULL)
    return NULL;
  ssize_t n = tunnel_vpn_dns_dequeue_query_wait(buf, (size_t)max_len, 50);
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

static jint native_queue_vpn_dns_response(JNIEnv *env, jclass clazz, jbyteArray jpacket) {
  (void)clazz;
  if (jpacket == NULL)
    return (jint)TUNNEL_EXIT_BAD_ARGS;
  jsize len = (*env)->GetArrayLength(env, jpacket);
  if (len <= 0)
    return (jint)TUNNEL_EXIT_BAD_ARGS;
  jbyte *bytes = (*env)->GetByteArrayElements(env, jpacket, NULL);
  if (bytes == NULL)
    return (jint)TUNNEL_EXIT_BAD_ARGS;
  int rc = tunnel_vpn_dns_write_response_packet((const uint8_t *)bytes, (size_t)len);
  (*env)->ReleaseByteArrayElements(env, jpacket, bytes, JNI_ABORT);
  return (jint)rc;
}

static void native_stop_tunnel(JNIEnv *env, jclass clazz) {
  (void)env;
  (void)clazz;
  tunnel_loop_stop();
}

static int register_vpn_bridge(JNIEnv *env) {
  static const JNINativeMethod methods[] = {
      {"nativeRunTunnel", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)I",
       (void *)native_run_tunnel},
      {"nativeNegotiate", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I[I[I[I)I",
       (void *)native_negotiate},
      {"nativeSetSocketProtectionEnabled", "(Z)V", (void *)native_set_socket_protection_enabled},
      {"nativeStartLoop", "(I)I", (void *)native_start_loop},
      {"nativeStartProxyLoop", "()I", (void *)native_start_proxy_loop},
      {"nativeIsProxyPacketBridgeActive", "()Z", (void *)native_is_proxy_packet_bridge_active},
      {"nativeQueueProxyOutboundPacket", "([B)I", (void *)native_queue_proxy_outbound_packet},
      {"nativeReadProxyInboundPacket", "(I)[B", (void *)native_read_proxy_inbound_packet},
      {"nativeSetVpnDnsInterceptIpv4", "(Ljava/lang/String;)I", (void *)native_set_vpn_dns_intercept_ipv4},
      {"nativeReadVpnDnsQuery", "(I)[B", (void *)native_read_vpn_dns_query},
      {"nativeQueueVpnDnsResponse", "([B)I", (void *)native_queue_vpn_dns_response},
      {"nativeStopTunnel", "()V", (void *)native_stop_tunnel},
  };
  jclass bridge_class = (*env)->FindClass(env, VPN_BRIDGE_CLASS);
  if (bridge_class == NULL) {
    (*env)->ExceptionClear(env);
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "JNI_OnLoad: could not find %s", VPN_BRIDGE_CLASS);
    return -1;
  }
  if ((*env)->RegisterNatives(env, bridge_class, methods, (jint)(sizeof(methods) / sizeof(methods[0]))) != JNI_OK) {
    (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, bridge_class);
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "JNI_OnLoad: RegisterNatives failed for %s", VPN_BRIDGE_CLASS);
    return -1;
  }
  (*env)->DeleteLocalRef(env, bridge_class);
  return 0;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  (void)reserved;
  JNIEnv *env = NULL;
  if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK || env == NULL) {
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "JNI_OnLoad: GetEnv failed");
    return JNI_ERR;
  }
  engine_set_java_vm(vm);
  if (engine_jni_init(env) != 0) {
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "JNI_OnLoad: engine_jni_init failed");
    return JNI_ERR;
  }
  if (register_vpn_bridge(env) != 0) {
    engine_jni_cleanup(env);
    return JNI_ERR;
  }
  return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
  (void)reserved;
  JNIEnv *env = NULL;
  if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK && env != NULL) {
    engine_jni_cleanup(env);
  }
  engine_set_java_vm(NULL);
}
