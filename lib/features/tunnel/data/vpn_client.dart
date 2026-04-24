// VPN [MethodChannel] client: Flutter invokes prepare/connect/disconnect; Android pushes state and engine logs.
import 'package:flutter/services.dart';

import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/core/logging/log_entry.dart';
import 'package:tunnel_forge/features/tunnel/data/vpn_contract.dart';

/// Host -> Dart: [VpnTunnelState] value, a human-readable [detail], and the originating [attemptId].
typedef VpnTunnelHostCallback =
    void Function(String state, String detail, String attemptId);

/// Host -> Dart: one engine log line ([VpnContract.argEngineLogLevel] is an Android log priority).
typedef VpnEngineLogCallback =
    void Function(LogLevel level, LogSource source, String tag, String message);

/// Host -> Dart: current proxy listener exposure after startup or shutdown.
typedef VpnProxyExposureCallback = void Function(ProxyExposure exposure);

class VpnClient {
  VpnClient({
    MethodChannel? channel,
    VpnTunnelHostCallback? onTunnelState,
    VpnEngineLogCallback? onEngineLog,
    VpnProxyExposureCallback? onProxyExposureChanged,
  }) : _channel = channel ?? MethodChannel(VpnContract.channel),
       _onTunnelState = onTunnelState,
       _onEngineLog = onEngineLog,
       _onProxyExposureChanged = onProxyExposureChanged {
    _channel.setMethodCallHandler(_handleHostCall);
  }

  final MethodChannel _channel;
  final VpnTunnelHostCallback? _onTunnelState;
  final VpnEngineLogCallback? _onEngineLog;
  final VpnProxyExposureCallback? _onProxyExposureChanged;

  Future<dynamic> _handleHostCall(MethodCall call) async {
    if (call.method == VpnContract.onTunnelState) {
      final raw = call.arguments;
      if (raw is Map) {
        final state = raw[VpnContract.argTunnelState]?.toString() ?? '';
        final detail = raw[VpnContract.argTunnelDetail]?.toString() ?? '';
        final attemptId = raw[VpnContract.argAttemptId]?.toString() ?? '';
        _onTunnelState?.call(state, detail, attemptId);
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
      return;
    }
    if (call.method == VpnContract.onProxyExposureChanged) {
      final exposure = ProxyExposure.tryFromMap(
        call.arguments,
        activeKey: VpnContract.argProxyExposureActive,
        bindAddressKey: VpnContract.argProxyExposureBindAddress,
        displayAddressKey: VpnContract.argProxyExposureDisplayAddress,
        httpPortKey: VpnContract.argProxyExposureHttpPort,
        socksPortKey: VpnContract.argProxyExposureSocksPort,
        lanRequestedKey: VpnContract.argProxyExposureLanRequested,
        lanActiveKey: VpnContract.argProxyExposureLanActive,
        warningKey: VpnContract.argProxyExposureWarning,
      );
      if (exposure != null) {
        _onProxyExposureChanged?.call(exposure);
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
    bool dnsAutomatic = true,
    List<DnsServerConfig> dnsServers = const [],
    int mtu = Profile.defaultVpnMtu,
    SplitTunnelSettings splitTunnelSettings = const SplitTunnelSettings(),
    ProxySettings proxySettings = const ProxySettings(),
  }) {
    final mtuClamped = Profile.normalizeMtu(mtu);
    // Flutter keeps DNS slot ordering explicit so Android can preserve DNS 1
    // as primary and DNS 2 as fallback without reparsing free-form text.
    final normalizedDnsServers = dnsServers
        .map(
          (entry) => <String, Object?>{
            VpnContract.argDnsServerHost: Profile.normalizeDnsServer(
              entry.host,
            ),
            VpnContract.argDnsServerProtocol: entry.protocol.jsonValue,
          },
        )
        .toList(growable: false);
    return _channel.invokeMethod<void>(VpnContract.connect, <String, Object?>{
      if (attemptId.isNotEmpty) VpnContract.argAttemptId: attemptId,
      VpnContract.argServer: server,
      if (profileName != null && profileName.isNotEmpty)
        VpnContract.argProfileName: profileName,
      VpnContract.argUser: user,
      VpnContract.argPassword: password,
      VpnContract.argPsk: psk,
      VpnContract.argDnsAutomatic: dnsAutomatic,
      VpnContract.argDnsServers: normalizedDnsServers,
      VpnContract.argMtu: mtuClamped,
      VpnContract.argConnectionMode: connectionMode.jsonValue,
      VpnContract.argSplitTunnelEnabled: splitTunnelSettings.enabled,
      VpnContract.argSplitTunnelMode: splitTunnelSettings.mode.jsonValue,
      VpnContract.argSplitTunnelInclusivePackages: List<String>.from(
        splitTunnelSettings.inclusivePackages,
      ),
      VpnContract.argSplitTunnelExclusivePackages: List<String>.from(
        splitTunnelSettings.exclusivePackages,
      ),
      VpnContract.argProxyHttpPort: ProxySettings.normalizePort(
        proxySettings.httpPort,
        fallback: ProxySettings.defaultHttpPort,
      ),
      VpnContract.argProxySocksPort: ProxySettings.normalizePort(
        proxySettings.socksPort,
        fallback: ProxySettings.defaultSocksPort,
      ),
      VpnContract.argProxyAllowLan: proxySettings.allowLanConnections,
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
