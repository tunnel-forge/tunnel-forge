/// Method-channel API contract: names and argument keys for the Android VPN bridge.
///
/// Dart calls [prepareVpn], [connect], and [disconnect]. The service may invoke
/// [onTunnelState] and [onEngineLog] on the same channel. Native code must use these exact strings.
abstract final class VpnContract {
  static const String channel = 'io.github.evokelektrique.tunnelforge/vpn';

  static const String prepareVpn = 'prepareVpn';
  static const String connect = 'connect';
  static const String disconnect = 'disconnect';
  static const String setLogLevel = 'setLogLevel';
  static const String getRuntimeState = 'getRuntimeState';

  /// Correlates one connect attempt across Dart UI, logs, and Android logcat.
  static const String argAttemptId = 'attemptId';
  static const String argLogLevel = 'logLevel';

  static const String argServer = 'server';
  static const String argUser = 'user';
  static const String argPassword = 'password';
  static const String argPsk = 'psk';
  static const String argDnsAutomatic = 'dnsAutomatic';
  static const String argDnsServers = 'dnsServers';
  static const String argDnsServerHost = 'host';
  static const String argDnsServerProtocol = 'protocol';
  static const String argMtu = 'mtu';
  static const String argProfileName = 'profileName';
  static const String argConnectionMode = 'connectionMode';
  static const String argProxyHttpPort = 'proxyHttpPort';
  static const String argProxySocksPort = 'proxySocksPort';
  static const String argProxyAllowLan = 'proxyAllowLan';
  static const String argSplitTunnelEnabled = 'splitTunnelEnabled';
  static const String argSplitTunnelMode = 'splitTunnelMode';
  static const String argSplitTunnelInclusivePackages =
      'splitTunnelInclusivePackages';
  static const String argSplitTunnelExclusivePackages =
      'splitTunnelExclusivePackages';

  /// Android: launcher-visible apps as `{packageName, label}` maps.
  static const String listVpnCandidateApps = 'listVpnCandidateApps';

  /// Android: argument is package name; response is launcher icon PNG bytes or null.
  static const String getAppIcon = 'getAppIcon';

  static const String modeVpnTunnel = 'vpnTunnel';
  static const String modeProxyOnly = 'proxyOnly';
  static const String splitTunnelModeInclusive = 'inclusive';
  static const String splitTunnelModeExclusive = 'exclusive';

  static const String onTunnelState = 'onTunnelState';

  static const String argTunnelState = 'tunnelState';
  static const String argTunnelDetail = 'tunnelDetail';

  /// Android -> Dart: engine log lines; [argEngineLogLevel] uses `android.util.Log` priorities.
  static const String onEngineLog = 'onEngineLog';

  static const String argEngineLogLevel = 'engineLogLevel';
  static const String argEngineLogSource = 'engineLogSource';
  static const String argEngineLogTag = 'engineLogTag';
  static const String argEngineLogMessage = 'engineLogMessage';

  /// Android -> Dart: active local-proxy listener address/port exposure.
  static const String onProxyExposureChanged = 'onProxyExposureChanged';

  static const String argProxyExposureActive = 'proxyExposureActive';
  static const String argProxyExposureBindAddress = 'proxyExposureBindAddress';
  static const String argProxyExposureDisplayAddress =
      'proxyExposureDisplayAddress';
  static const String argProxyExposureHttpPort = 'proxyExposureHttpPort';
  static const String argProxyExposureSocksPort = 'proxyExposureSocksPort';
  static const String argProxyExposureLanRequested =
      'proxyExposureLanRequested';
  static const String argProxyExposureLanActive = 'proxyExposureLanActive';
  static const String argProxyExposureWarning = 'proxyExposureWarning';
}

/// Tunnel lifecycle strings sent with [VpnContract.onTunnelState] / [VpnContract.argTunnelState].
abstract final class VpnTunnelState {
  static const String connecting = 'connecting';
  static const String connected = 'connected';
  static const String failed = 'failed';
  static const String stopped = 'stopped';
}
