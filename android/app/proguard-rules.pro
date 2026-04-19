# JNI: native code resolves these by name (libtunnel_engine.so).
-keep class io.github.evokelektrique.tunnelforge.TunnelVpnService {
    public static boolean protectSocketFd(int);
}
-keep class io.github.evokelektrique.tunnelforge.VpnBridge { *; }
-keep class io.github.evokelektrique.tunnelforge.VpnTunnelEvents {
    public static void emitEngineLogFromNative(int, java.lang.String, java.lang.String);
}
