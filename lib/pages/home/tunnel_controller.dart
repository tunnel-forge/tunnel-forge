part of '../home_page.dart';

/// Connect/disconnect, attempt IDs, Android state callbacks, and 60s ready timeout.
extension _VpnHomePageTunnel on _VpnHomePageState {
  void _cancelAwaitTimer() {
    _awaitTimer?.cancel();
    _awaitTimer = null;
  }

  /// Short-lived id for log correlation (not guaranteed globally unique).
  String _newAttemptId() {
    final ms = DateTime.now().millisecondsSinceEpoch;
    final r = (ms ^ (ms >> 7) ^ (ms << 3)) & 0xFFFFFF;
    return '${ms.toRadixString(36)}-${r.toRadixString(36).padLeft(4, '0')}';
  }

  static String _androidLogLetter(int p) {
    if (p <= 2) return 'V';
    if (p == 3) return 'D';
    if (p == 4) return 'I';
    if (p == 5) return 'W';
    if (p == 6) return 'E';
    if (p >= 7) return 'F';
    return '?';
  }

  void _onEngineLogFromHost(int level, String tag, String message) {
    if (!mounted) return;
    _log('[${_androidLogLetter(level)}/$tag] $message');
  }

  void _onTunnelFromHost(String state, String detail) {
    if (!mounted) return;
    final proxyMode = _connectionMode == ConnectionMode.proxyOnly;
    if (_timedOutThisAttempt &&
        (state == VpnTunnelState.connected || state == VpnTunnelState.failed)) {
      final startedAt = _connectStartedAt;
      final elapsed = startedAt == null
          ? null
          : DateTime.now().difference(startedAt);
      final attempt = _activeAttemptId;
      _log(
        'Late Android event after timeout: state=$state${attempt == null ? '' : ' attempt=$attempt'}${elapsed == null ? '' : ' after_ms=${elapsed.inMilliseconds}'}',
      );
      _timedOutThisAttempt = false;
    }
    switch (state) {
      case VpnTunnelState.connecting:
        _log(
          'Android${_activeAttemptId == null ? '' : ' attempt=${_activeAttemptId!}'}: $detail',
        );
        if (_awaitingTunnel && !_tunnelUp) _scheduleAwaitTimeout();
        break;
      case VpnTunnelState.connected:
        _cancelAwaitTimer();
        _setHomeState(() {
          _awaitingTunnel = false;
          _tunnelUp = true;
        });
        _log(
          'Android${_activeAttemptId == null ? '' : ' attempt=${_activeAttemptId!}'}: ${proxyMode ? 'proxy ready' : 'TUN is up'}: $detail',
        );
        _toast(proxyMode ? 'Proxy ready' : 'VPN connected');
        unawaited(_runConnectivityCheck());
        break;
      case VpnTunnelState.failed:
        _cancelAwaitTimer();
        _setHomeState(() {
          _awaitingTunnel = false;
          _tunnelUp = false;
        });
        _log(
          'Android${_activeAttemptId == null ? '' : ' attempt=${_activeAttemptId!}'}: tunnel failed: $detail',
        );
        _toast(
          detail.isEmpty ? 'Couldn\'t establish the tunnel' : detail,
          error: true,
        );
        break;
      case VpnTunnelState.stopped:
        _cancelAwaitTimer();
        _setHomeState(() {
          _awaitingTunnel = false;
          _tunnelUp = false;
        });
        _log(
          'Android${_activeAttemptId == null ? '' : ' attempt=${_activeAttemptId!}'}: tunnel stopped: $detail',
        );
        break;
      default:
        _log(
          'Android${_activeAttemptId == null ? '' : ' attempt=${_activeAttemptId!}'}: $state${detail.isEmpty ? '' : ': $detail'}',
        );
    }
  }

  void _scheduleAwaitTimeout() {
    _cancelAwaitTimer();
    _awaitTimer = Timer(const Duration(seconds: 60), () {
      if (!mounted) return;
      if (_awaitingTunnel && !_tunnelUp) {
        _setHomeState(() => _awaitingTunnel = false);
        _timedOutThisAttempt = true;
        final startedAt = _connectStartedAt;
        final elapsed = startedAt == null
            ? null
            : DateTime.now().difference(startedAt);
        final attempt = _activeAttemptId;
        _log(
          'Timeout waiting for ${_connectionMode == ConnectionMode.proxyOnly ? 'proxy service' : 'VPN interface'} from Android${attempt == null ? '' : ' attempt=$attempt'}${elapsed == null ? '' : ' elapsed_ms=${elapsed.inMilliseconds}'}',
        );
        _toast('Still connecting. Check logs or try again.', error: true);
      }
    });
  }

  String get _connectButtonLabel {
    if (_busy && _tunnelUp) return 'Disconnecting...';
    if (_awaitingTunnel && !_tunnelUp) return 'Connecting...';
    if (_busy) return 'Working...';
    if (_tunnelUp) {
      return _connectionMode == ConnectionMode.proxyOnly
          ? 'Proxy ready'
          : 'Connected';
    }
    if (!_hasActiveProfile) return 'Select profile';
    return 'Ready';
  }

  Future<void> _primaryAction() async {
    if (_busy || (_awaitingTunnel && !_tunnelUp)) return;
    if (_tunnelUp) return _disconnect();
    if (!_hasActiveProfile) {
      _toast('Select a saved profile first', error: true);
      _log('Connect blocked: no active profile');
      return;
    }
    return _connectWithAutoPrepare();
  }

  Future<void> _connectWithAutoPrepare() async {
    final proxyMode = _connectionMode == ConnectionMode.proxyOnly;
    final host = _server.text.trim();
    if (!proxyMode && host.isEmpty) {
      _toast('Enter a server hostname or address', error: true);
      _log('Connect blocked: empty server');
      return;
    }
    if (proxyMode && _activeProfileId == null) {
      _toast('Select a saved profile to start local proxy', error: true);
      _log('Connect blocked: proxy mode requires a saved profile');
      return;
    }
    if (proxyMode &&
        !_proxySettings.httpEnabled &&
        !_proxySettings.socksEnabled) {
      _toast('Enable HTTP or SOCKS5 before connecting', error: true);
      _log('Connect blocked: proxy mode has no enabled listeners');
      return;
    }
    if (!proxyMode &&
        _routingMode == RoutingMode.perAppAllowList &&
        _allowedAppPackages.isEmpty) {
      _toast(
        'Turn on "VPN for all apps", or open "Choose apps" and select at least one app.',
        error: true,
      );
      _log('Connect blocked: per-app mode with empty allow-list');
      return;
    }

    final attemptId = _newAttemptId();
    _setHomeState(() => _busy = true);
    _activeAttemptId = attemptId;
    _connectStartedAt = DateTime.now();
    _timedOutThisAttempt = false;

    try {
      if (!proxyMode) {
        _log('Requesting VPN permission (if needed)... attempt=$attemptId');
        final ok = await _client.prepareVpn();
        if (!ok) {
          _log('VPN permission denied or cancelled attempt=$attemptId');
          _toast('VPN permission is required', error: true);
          return;
        }
        _log('VPN permission OK attempt=$attemptId');
      } else {
        _log(
          'Starting local proxy mode without Android VPN permission... attempt=$attemptId',
        );
      }

      _log(
        'Sending connect to ${proxyMode ? 'proxy service' : 'tunnel service'}... attempt=$attemptId',
      );
      final invalidDns = Profile.firstInvalidDnsServer(_dns.text);
      if (invalidDns != null) {
        _log('Connect blocked: invalid DNS server "$invalidDns"');
        _toast(
          'DNS server "$invalidDns" is not a valid IPv4 address',
          error: true,
        );
        return;
      }
      final dnsServers = Profile.dnsServersFromText(_dns.text);
      final mtu = Profile.mtuFromText(
        _mtu.text,
        fallback: Profile.defaultVpnMtu,
      );
      String? profileName;
      for (final profile in _profiles) {
        if (profile.id == _activeProfileId) {
          final trimmed = profile.displayName.trim();
          profileName = trimmed.isEmpty ? null : trimmed;
          break;
        }
      }
      _log(
        'Profile: server=$host user=${_user.text.isEmpty ? '(empty)' : _user.text} dns=${dnsServers.join(', ')} mtu=$mtu '
        'psk=${_psk.text.isEmpty ? 'off (cleartext L2TP if server allows)' : 'on'} '
        'mode=${_connectionMode.jsonValue} routing=${_routingMode.jsonValue} allowedApps=${_allowedAppPackages.length} '
        'http=${_proxySettings.httpEnabled ? _proxySettings.httpPort : 'off'} socks=${_proxySettings.socksEnabled ? _proxySettings.socksPort : 'off'} '
        'attempt=$attemptId',
      );
      await _client.connect(
        attemptId: attemptId,
        server: host,
        profileName: profileName,
        connectionMode: _connectionMode,
        user: _user.text,
        password: _password.text,
        psk: _psk.text,
        dnsServers: dnsServers,
        mtu: mtu,
        routingMode: _routingMode,
        allowedAppPackages: _allowedAppPackages,
        proxySettings: _proxySettings,
      );
      _log(
        'Connect acknowledged; waiting for ${proxyMode ? 'proxy readiness' : 'TUN'} from Android... attempt=$attemptId',
      );
      if (!mounted) return;
      _setHomeState(() {
        _awaitingTunnel = true;
        _tunnelUp = false;
      });
      _scheduleAwaitTimeout();
      _toast(proxyMode ? 'Starting proxy...' : 'Connecting...');
    } on PlatformException catch (e) {
      _log('Platform error: ${e.code} ${e.message ?? ''} attempt=$attemptId');
      _toast(e.message ?? e.code, error: true);
    } finally {
      _setHomeStateIfMounted(() => _busy = false);
    }
  }

  Future<void> _disconnect() async {
    _setHomeState(() => _busy = true);
    _log('Disconnect requested...');
    try {
      await _client.disconnect();
      _log('Disconnect dispatched; waiting for Android stopped event...');
      _cancelAwaitTimer();
    } on PlatformException catch (e) {
      _log('Disconnect error: ${e.code} ${e.message ?? ''}');
      _toast(e.message ?? e.code, error: true);
    } finally {
      _setHomeStateIfMounted(() => _busy = false);
    }
  }
}
