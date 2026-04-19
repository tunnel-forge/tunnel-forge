part of '../home_page.dart';

extension _VpnHomePageConnectivity on _VpnHomePageState {
  String get _connectivityBadgeLabel {
    return switch (_connectivityBadgeState) {
      ConnectivityBadgeState.idle => 'Tap to check',
      ConnectivityBadgeState.checking => 'Checking...',
      ConnectivityBadgeState.success => '${_connectivityLatencyMs ?? 0} ms',
      ConnectivityBadgeState.failure => 'Unreachable',
    };
  }

  Future<void> _runConnectivityCheck() async {
    if (!mounted ||
        !_tunnelUp ||
        _busy ||
        _connectivityBadgeState == ConnectivityBadgeState.checking) {
      return;
    }

    final url = _connectivityCheckSettings.url;
    _setHomeState(() {
      _connectivityBadgeState = ConnectivityBadgeState.checking;
    });
    _logDebug('Connectivity check: $url', tag: 'connectivity');

    final result = await _connectivityChecker.ping(url);
    if (!mounted) return;

    if (result.reachable && result.latencyMs != null) {
      _setHomeState(() {
        _connectivityBadgeState = ConnectivityBadgeState.success;
        _connectivityLatencyMs = result.latencyMs;
      });
      _logInfo(
        'Connectivity OK: ${result.latencyMs} ms'
        '${result.statusCode == null ? '' : ' status=${result.statusCode}'}',
        tag: 'connectivity',
      );
      return;
    }

    _setHomeState(() {
      _connectivityBadgeState = ConnectivityBadgeState.failure;
      _connectivityLatencyMs = null;
    });
    _logWarning(
      'Connectivity failed'
      '${result.statusCode == null ? '' : ' status=${result.statusCode}'}'
      '${result.error == null || result.error!.isEmpty ? '' : ': ${result.error}'}',
      tag: 'connectivity',
    );
  }
}
