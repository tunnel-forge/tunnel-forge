# JNI: RegisterNatives binds VpnBridge explicitly; native helpers still resolve these Kotlin peers.
-keep class io.github.evokelektrique.tunnelforge.TunnelVpnService {
    public static boolean protectSocketFd(int);
}
-keep class io.github.evokelektrique.tunnelforge.VpnBridge { *; }
-keep class io.github.evokelektrique.tunnelforge.VpnTunnelEvents {
    public static void emitEngineLogFromNative(int, java.lang.String, java.lang.String);
}
