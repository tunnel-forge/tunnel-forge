# JNI: native code resolves these by name (libtunnel_engine.so).
-keep class com.example.tunnel_forge.TunnelVpnService {
    public static boolean protectSocketFd(int);
}
-keep class com.example.tunnel_forge.VpnBridge { *; }
-keep class com.example.tunnel_forge.VpnTunnelEvents {
    public static void emitEngineLogFromNative(int, java.lang.String, java.lang.String);
}
