# JNI: RegisterNatives binds VpnBridge explicitly; native helpers still resolve these Kotlin peers.
-keep class io.github.evokelektrique.tunnelforge.TunnelVpnService {
    public static boolean protectSocketFd(int);
}
-keep class io.github.evokelektrique.tunnelforge.VpnBridge { *; }
-keep class io.github.evokelektrique.tunnelforge.VpnTunnelEvents {
    public static void emitEngineLogFromNative(int, java.lang.String, java.lang.String);
}

# Netty probes optional JVM/server integrations that are not present on Android.
-dontwarn jdk.jfr.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn reactor.blockhound.**

# Optional Netty SSL/compression/native integrations are not shipped on Android.
-dontwarn io.netty.internal.tcnative.**
-dontwarn io.netty.pkitesting.**
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.github.luben.zstd.**
-dontwarn com.jcraft.jzlib.**
-dontwarn com.ning.compress.**
-dontwarn com.oracle.svm.core.annotate.**
-dontwarn javax.naming.ldap.**
-dontwarn lzma.sdk.**
-dontwarn net.jpountz.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**

# Netty contains several Android-hostile reflective/string-name probes. Keep it unoptimized/ unobfuscated
# so release R8 does not break Netty internals while the rest of the app can still be minified.
-keep class io.netty.** { *; }
