// VPN [MethodChannel] client: Flutter invokes prepare/connect/disconnect; Android pushes state and engine logs.
import 'package:flutter/services.dart';

import 'profile_models.dart';
import 'utils/log_entry.dart';
import 'vpn_contract.dart';

/// Host → Dart: [VpnTunnelState] value and a human-readable [detail] string.
typedef VpnTunnelHostCallback = void Function(String state, String detail);

/// Host → Dart: one engine log line ([VpnContract.argEngineLogLevel] is an Android log priority).
typedef VpnEngineLogCallback =
    void Function(LogLevel level, LogSource source, String tag, String message);

class VpnClient {
  VpnClient({
    MethodChannel? channel,
    VpnTunnelHostCallback? onTunnelState,
    VpnEngineLogCallback? onEngineLog,
  }) : _channel = channel ?? MethodChannel(VpnContract.channel),
       _onTunnelState = onTunnelState,
       _onEngineLog = onEngineLog {
    _channel.setMethodCallHandler(_handleHostCall);
  }

  final MethodChannel _channel;
  final VpnTunnelHostCallback? _onTunnelState;
  final VpnEngineLogCallback? _onEngineLog;

  Future<dynamic> _handleHostCall(MethodCall call) async {
    if (call.method == VpnContract.onTunnelState) {
      final raw = call.arguments;
      if (raw is Map) {
        final state = raw[VpnContract.argTunnelState]?.toString() ?? '';
        final detail = raw[VpnContract.argTunnelDetail]?.toString() ?? '';
        _onTunnelState?.call(state, detail);
      }
      return;
    }
    if (call.method == VpnContract.onEngineLog) {
      final raw = call.arguments;
      if (raw is Map) {
        final levelRaw = raw[VpnContract.argEngineLogLevel];
        final level = switch (levelRaw) {
          int v => v,
          _ => int.tryParse(levelRaw?.toString() ?? '') ?? 4,
        };
        final source = LogSource.parse(
          raw[VpnContract.argEngineLogSource]?.toString(),
        );
        final tag =
            raw[VpnContract.argEngineLogTag]?.toString() ?? 'tunnel_engine';
        final message = raw[VpnContract.argEngineLogMessage]?.toString() ?? '';
        _onEngineLog?.call(
          LogLevel.fromAndroidPriority(level),
          source,
          tag,
          message,
        );
      }
    }
  }

  /// Clears the inbound method handler; call when the owning widget is disposed.
  void dispose() {
    _channel.setMethodCallHandler(null);
  }

  Future<bool> prepareVpn() async {
    final v = await _channel.invokeMethod<Object>(VpnContract.prepareVpn);
    if (v is bool) return v;
    return false;
  }

  Future<void> connect({
    String attemptId = '',
    required String server,
    String? profileName,
    ConnectionMode connectionMode = ConnectionMode.vpnTunnel,
    String user = '',
    String password = '',
    String psk = '',
    List<String> dnsServers = const [Profile.defaultDns],
    int mtu = Profile.defaultVpnMtu,
    RoutingMode routingMode = RoutingMode.fullTunnel,
    List<String> allowedAppPackages = const [],
    ProxySettings proxySettings = const ProxySettings(),
  }) {
    final mtuClamped = Profile.normalizeMtu(mtu);
    final normalizedDnsServers = Profile.dnsServersFromText(
      dnsServers.join(','),
    );
    return _channel.invokeMethod<void>(VpnContract.connect, <String, Object?>{
      if (attemptId.isNotEmpty) VpnContract.argAttemptId: attemptId,
      VpnContract.argServer: server,
      if (profileName != null && profileName.isNotEmpty)
        VpnContract.argProfileName: profileName,
      VpnContract.argUser: user,
      VpnContract.argPassword: password,
      VpnContract.argPsk: psk,
      VpnContract.argDns: normalizedDnsServers.first,
      VpnContract.argDnsServers: List<String>.from(normalizedDnsServers),
      VpnContract.argMtu: mtuClamped,
      VpnContract.argConnectionMode: connectionMode.jsonValue,
      VpnContract.argRoutingMode: routingMode.jsonValue,
      VpnContract.argAllowedPackages: List<String>.from(allowedAppPackages),
      VpnContract.argProxyHttpEnabled: proxySettings.httpEnabled,
      VpnContract.argProxyHttpPort: ProxySettings.normalizePort(
        proxySettings.httpPort,
        fallback: ProxySettings.defaultHttpPort,
      ),
      VpnContract.argProxySocksEnabled: proxySettings.socksEnabled,
      VpnContract.argProxySocksPort: ProxySettings.normalizePort(
        proxySettings.socksPort,
        fallback: ProxySettings.defaultSocksPort,
      ),
    });
  }

  /// Launcher icon as PNG bytes, or null (Android; missing plugin returns null).
  Future<Uint8List?> getAppIcon(String packageName) async {
    if (packageName.isEmpty) return null;
    try {
      final raw = await _channel.invokeMethod<Object>(
        VpnContract.getAppIcon,
        packageName,
      );
      if (raw is Uint8List) return raw;
      if (raw is List<int>) return Uint8List.fromList(raw);
      return null;
    } on PlatformException {
      return null;
    } on MissingPluginException {
      return null;
    }
  }

  /// Launcher-visible apps (Android). Empty when unsupported or the platform errors.
  Future<List<CandidateApp>> listVpnCandidateApps() async {
    try {
      final raw = await _channel.invokeMethod<Object>(
        VpnContract.listVpnCandidateApps,
      );
      if (raw is! List) return [];
      final out = <CandidateApp>[];
      for (final e in raw) {
        final row = CandidateApp.tryFromMap(e);
        if (row != null) out.add(row);
      }
      out.sort(
        (a, b) => a.label.toLowerCase().compareTo(b.label.toLowerCase()),
      );
      return out;
    } on PlatformException {
      return [];
    } on MissingPluginException {
      return [];
    }
  }

  Future<void> disconnect() =>
      _channel.invokeMethod<void>(VpnContract.disconnect);

  Future<void> setLogLevel(LogDisplayLevel level) {
    return _channel.invokeMethod<void>(
      VpnContract.setLogLevel,
      <String, Object?>{VpnContract.argLogLevel: level.storageValue},
    );
  }
}
