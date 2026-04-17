/// Method-channel API contract: names and argument keys for the Android VPN bridge.
///
/// Dart calls [prepareVpn], [connect], and [disconnect]. The service may invoke
/// [onTunnelState] and [onEngineLog] on the same channel. Native code must use these exact strings.
abstract final class VpnContract {
  static const String channel = 'com.example.tunnel_forge/vpn';

  static const String prepareVpn = 'prepareVpn';
  static const String connect = 'connect';
  static const String disconnect = 'disconnect';

  /// Correlates one connect attempt across Dart UI, logs, and Android logcat.
  static const String argAttemptId = 'attemptId';

  static const String argServer = 'server';
  static const String argUser = 'user';
  static const String argPassword = 'password';
  static const String argPsk = 'psk';
  static const String argDns = 'dns';
  static const String argMtu = 'mtu';
  static const String argProfileName = 'profileName';

  /// `fullTunnel` or `perAppAllowList` — same values as [RoutingMode.jsonValue].
  static const String argRoutingMode = 'routingMode';

  /// Package allow-list when [argRoutingMode] is `perAppAllowList`; ignored for full tunnel.
  static const String argAllowedPackages = 'allowedPackages';

  /// Android: launcher-visible apps as `{packageName, label}` maps.
  static const String listVpnCandidateApps = 'listVpnCandidateApps';

  /// Android: argument is package name; response is launcher icon PNG bytes or null.
  static const String getAppIcon = 'getAppIcon';

  static const String onTunnelState = 'onTunnelState';

  static const String argTunnelState = 'tunnelState';
  static const String argTunnelDetail = 'tunnelDetail';

  /// Android → Dart: engine log lines; [argEngineLogLevel] uses `android.util.Log` priorities.
  static const String onEngineLog = 'onEngineLog';

  static const String argEngineLogLevel = 'engineLogLevel';
  static const String argEngineLogTag = 'engineLogTag';
  static const String argEngineLogMessage = 'engineLogMessage';
}

/// Tunnel lifecycle strings sent with [VpnContract.onTunnelState] / [VpnContract.argTunnelState].
abstract final class VpnTunnelState {
  static const String connecting = 'connecting';
  static const String connected = 'connected';
  static const String failed = 'failed';
  static const String stopped = 'stopped';
}
