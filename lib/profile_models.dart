/// Connection surface: Android VPN/TUN vs. local proxy listeners only.
enum ConnectionMode {
  vpnTunnel('vpnTunnel'),
  proxyOnly('proxyOnly');

  const ConnectionMode(this.jsonValue);
  final String jsonValue;

  static ConnectionMode fromJson(Object? raw) {
    if (raw is! String) return ConnectionMode.vpnTunnel;
    return switch (raw) {
      'proxyOnly' => ConnectionMode.proxyOnly,
      _ => ConnectionMode.vpnTunnel,
    };
  }
}

/// Split-tunneling behavior when it is enabled.
enum SplitTunnelMode {
  inclusive('inclusive'),
  exclusive('exclusive');

  const SplitTunnelMode(this.jsonValue);
  final String jsonValue;

  static SplitTunnelMode fromJson(Object? raw) {
    if (raw is! String) return SplitTunnelMode.inclusive;
    return switch (raw) {
      'exclusive' => SplitTunnelMode.exclusive,
      _ => SplitTunnelMode.inclusive,
    };
  }
}

/// Global split-tunneling settings shared across profiles.
class SplitTunnelSettings {
  const SplitTunnelSettings({
    this.enabled = false,
    this.mode = SplitTunnelMode.inclusive,
    this.inclusivePackages = const <String>[],
    this.exclusivePackages = const <String>[],
  });

  final bool enabled;
  final SplitTunnelMode mode;
  final List<String> inclusivePackages;
  final List<String> exclusivePackages;

  List<String> get activePackages =>
      mode == SplitTunnelMode.inclusive ? inclusivePackages : exclusivePackages;

  SplitTunnelSettings copyWith({
    bool? enabled,
    SplitTunnelMode? mode,
    List<String>? inclusivePackages,
    List<String>? exclusivePackages,
  }) {
    return SplitTunnelSettings(
      enabled: enabled ?? this.enabled,
      mode: mode ?? this.mode,
      inclusivePackages: normalizePackages(
        inclusivePackages ?? this.inclusivePackages,
      ),
      exclusivePackages: normalizePackages(
        exclusivePackages ?? this.exclusivePackages,
      ),
    );
  }

  static List<String> normalizePackages(Iterable<String> packages) {
    final next =
        packages
            .map((value) => value.trim())
            .where((value) => value.isNotEmpty)
            .toSet()
            .toList()
          ..sort();
    return List<String>.unmodifiable(next);
  }

  @override
  bool operator ==(Object other) {
    return other is SplitTunnelSettings &&
        enabled == other.enabled &&
        mode == other.mode &&
        _sameStringList(inclusivePackages, other.inclusivePackages) &&
        _sameStringList(exclusivePackages, other.exclusivePackages);
  }

  @override
  int get hashCode => Object.hash(
    enabled,
    mode,
    Object.hashAll(inclusivePackages),
    Object.hashAll(exclusivePackages),
  );

  static bool _sameStringList(List<String> a, List<String> b) {
    if (identical(a, b)) return true;
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }
}

/// Ordered by UI display and by the contract sent to Android.
enum DnsProtocol {
  dnsOverTcp('dnsOverTcp', 'TCP', 'DNS-over-TCP'),
  dnsOverUdp('dnsOverUdp', 'UDP', 'DNS-over-UDP'),
  dnsOverTls('dnsOverTls', 'TLS', 'DNS-over-TLS'),
  dnsOverHttps('dnsOverHttps', 'HTTPS', 'DNS-over-HTTPS');

  const DnsProtocol(this.jsonValue, this.shortLabel, this.displayLabel);

  final String jsonValue;
  final String shortLabel;
  final String displayLabel;

  bool get requiresHostname =>
      this == DnsProtocol.dnsOverTls || this == DnsProtocol.dnsOverHttps;

  static const List<DnsProtocol> orderedValues = <DnsProtocol>[
    DnsProtocol.dnsOverTcp,
    DnsProtocol.dnsOverUdp,
    DnsProtocol.dnsOverTls,
    DnsProtocol.dnsOverHttps,
  ];

  static DnsProtocol fromJson(Object? raw) {
    if (raw is! String) return DnsProtocol.dnsOverUdp;
    return switch (raw) {
      'dnsOverTcp' => DnsProtocol.dnsOverTcp,
      'dnsOverTls' => DnsProtocol.dnsOverTls,
      'dnsOverHttps' => DnsProtocol.dnsOverHttps,
      _ => DnsProtocol.dnsOverUdp,
    };
  }
}

class DnsServerConfig {
  const DnsServerConfig({required this.host, required this.protocol});

  final String host;
  final DnsProtocol protocol;

  DnsServerConfig copyWith({String? host, DnsProtocol? protocol}) {
    return DnsServerConfig(
      host: host ?? this.host,
      protocol: protocol ?? this.protocol,
    );
  }

  Map<String, Object?> toJson() => {
    'host': host,
    'protocol': protocol.jsonValue,
  };
}

/// Global local-proxy settings shared across VPN and local-proxy modes.
class ProxySettings {
  const ProxySettings({
    this.httpPort = defaultHttpPort,
    this.socksPort = defaultSocksPort,
    this.allowLanConnections = false,
  });

  static const int defaultHttpPort = 8080;
  static const int defaultSocksPort = 1080;
  static const int minPort = 1;
  static const int maxPort = 65535;

  final int httpPort;
  final int socksPort;
  final bool allowLanConnections;

  static int normalizePort(int value, {required int fallback}) {
    if (value < minPort || value > maxPort) return fallback;
    return value;
  }

  static int portFromText(String text, {required int fallback}) {
    final parsed = int.tryParse(text.trim());
    if (parsed == null) return fallback;
    return normalizePort(parsed, fallback: fallback);
  }

  ProxySettings copyWith({
    int? httpPort,
    int? socksPort,
    bool? allowLanConnections,
  }) {
    return ProxySettings(
      httpPort: httpPort ?? this.httpPort,
      socksPort: socksPort ?? this.socksPort,
      allowLanConnections: allowLanConnections ?? this.allowLanConnections,
    );
  }
}

/// Runtime proxy listener exposure reported by Android after the listeners start.
class ProxyExposure {
  const ProxyExposure({
    required this.active,
    required this.bindAddress,
    required this.displayAddress,
    required this.httpPort,
    required this.socksPort,
    required this.lanRequested,
    required this.lanActive,
    this.warning,
  });

  final bool active;
  final String bindAddress;
  final String displayAddress;
  final int httpPort;
  final int socksPort;
  final bool lanRequested;
  final bool lanActive;
  final String? warning;

  bool get hasWarning => warning != null && warning!.trim().isNotEmpty;

  static ProxyExposure? tryFromMap(
    Object? raw, {
    required String activeKey,
    required String bindAddressKey,
    required String displayAddressKey,
    required String httpPortKey,
    required String socksPortKey,
    required String lanRequestedKey,
    required String lanActiveKey,
    required String warningKey,
  }) {
    if (raw is! Map) return null;
    final bindAddress = raw[bindAddressKey]?.toString() ?? '';
    final displayAddress = raw[displayAddressKey]?.toString() ?? '';
    final httpPort = _parsePort(raw[httpPortKey]);
    final socksPort = _parsePort(raw[socksPortKey]);
    if (bindAddress.isEmpty ||
        displayAddress.isEmpty ||
        httpPort == null ||
        socksPort == null) {
      return null;
    }
    return ProxyExposure(
      active: _parseBool(raw[activeKey]),
      bindAddress: bindAddress,
      displayAddress: displayAddress,
      httpPort: httpPort,
      socksPort: socksPort,
      lanRequested: _parseBool(raw[lanRequestedKey]),
      lanActive: _parseBool(raw[lanActiveKey]),
      warning: raw[warningKey]?.toString(),
    );
  }

  static int? _parsePort(Object? raw) {
    return switch (raw) {
      int value => value,
      _ => int.tryParse(raw?.toString() ?? ''),
    };
  }

  static bool _parseBool(Object? raw) {
    return switch (raw) {
      bool value => value,
      String value => value.toLowerCase() == 'true',
      _ => false,
    };
  }

  @override
  bool operator ==(Object other) {
    return other is ProxyExposure &&
        active == other.active &&
        bindAddress == other.bindAddress &&
        displayAddress == other.displayAddress &&
        httpPort == other.httpPort &&
        socksPort == other.socksPort &&
        lanRequested == other.lanRequested &&
        lanActive == other.lanActive &&
        warning == other.warning;
  }

  @override
  int get hashCode => Object.hash(
    active,
    bindAddress,
    displayAddress,
    httpPort,
    socksPort,
    lanRequested,
    lanActive,
    warning,
  );
}

/// Global endpoint used by the connectivity badge for direct reachability checks.
class ConnectivityCheckSettings {
  const ConnectivityCheckSettings({
    this.url = defaultUrl,
    this.timeoutMs = defaultTimeoutMs,
  });

  static const String defaultUrl = 'https://www.gstatic.com/generate_204';
  static const int defaultTimeoutMs = 5000;

  final String url;
  final int timeoutMs;

  static String normalizeUrl(String text) {
    final trimmed = text.trim();
    return trimmed.isEmpty ? defaultUrl : trimmed;
  }

  static int normalizeTimeoutMs(int value) {
    return value > 0 ? value : defaultTimeoutMs;
  }

  static int? parseTimeoutMs(String text) {
    final trimmed = text.trim();
    if (trimmed.isEmpty) return defaultTimeoutMs;
    final value = int.tryParse(trimmed);
    if (value == null || value <= 0) return null;
    return value;
  }

  static String? validateUrl(String text) {
    final normalized = normalizeUrl(text);
    final uri = Uri.tryParse(normalized);
    if (uri == null || !uri.hasScheme || uri.host.isEmpty) {
      return 'Enter a valid absolute HTTP or HTTPS URL';
    }
    final scheme = uri.scheme.toLowerCase();
    if (scheme != 'http' && scheme != 'https') {
      return 'Only HTTP and HTTPS URLs are supported';
    }
    return null;
  }

  static String? validateTimeoutMs(String text) {
    if (text.trim().isEmpty) return null;
    final value = int.tryParse(text.trim());
    if (value == null) {
      return 'Enter a whole number of milliseconds';
    }
    if (value <= 0) {
      return 'Enter a timeout greater than 0 ms';
    }
    return null;
  }

  ConnectivityCheckSettings copyWith({String? url, int? timeoutMs}) {
    return ConnectivityCheckSettings(
      url: normalizeUrl(url ?? this.url),
      timeoutMs: normalizeTimeoutMs(timeoutMs ?? this.timeoutMs),
    );
  }

  @override
  bool operator ==(Object other) {
    return other is ConnectivityCheckSettings &&
        url == other.url &&
        timeoutMs == other.timeoutMs;
  }

  @override
  int get hashCode => Object.hash(url, timeoutMs);
}

/// One launchable app row from [VpnClient.listVpnCandidateApps].
class CandidateApp {
  const CandidateApp({required this.packageName, required this.label});

  final String packageName;
  final String label;

  static CandidateApp? tryFromMap(Object? raw) {
    if (raw is! Map) return null;
    final m = Map<String, dynamic>.from(raw);
    final pkg = m['packageName'];
    final label = m['label'];
    if (pkg is! String || label is! String) return null;
    if (pkg.isEmpty) return null;
    return CandidateApp(packageName: pkg, label: label);
  }
}

/// Saved VPN identity: public fields only; password and PSK live in [ProfileStore] secrets.
class Profile {
  const Profile({
    required this.id,
    required this.displayName,
    required this.server,
    required this.user,
    this.dnsAutomatic = true,
    this.dns1Host = '',
    this.dns1Protocol = DnsProtocol.dnsOverUdp,
    this.dns2Host = '',
    this.dns2Protocol = DnsProtocol.dnsOverUdp,
    this.mtu = defaultVpnMtu,
  });

  /// TUN interface MTU (bytes). Shared default for new profiles and quick-connect.
  static const int defaultVpnMtu = 1450;

  static const int minVpnMtu = 576;
  static const int maxVpnMtu = 1450;

  final String id;
  final String displayName;
  final String server;
  final String user;
  final bool dnsAutomatic;
  final String dns1Host;
  final DnsProtocol dns1Protocol;
  final String dns2Host;
  final DnsProtocol dns2Protocol;

  /// Android VpnService [Builder.setMtu]; clamped [minVpnMtu]–[maxVpnMtu].
  final int mtu;

  List<DnsServerConfig> get manualDnsServers => orderedDnsServers(
    dns1Host: dns1Host,
    dns1Protocol: dns1Protocol,
    dns2Host: dns2Host,
    dns2Protocol: dns2Protocol,
  );
  String? get invalidDns1 => invalidDnsServer(dns1Host, dns1Protocol);
  String? get invalidDns2 => invalidDnsServer(dns2Host, dns2Protocol);

  static int normalizeMtu(int value) => value.clamp(minVpnMtu, maxVpnMtu);

  /// Parses editor / free-form input; invalid or empty [text] returns [fallback].
  static int mtuFromText(String text, {int fallback = defaultVpnMtu}) {
    final v = int.tryParse(text.trim());
    if (v == null) return fallback;
    return normalizeMtu(v);
  }

  static bool isValidIpv4DnsServer(String value) {
    final parts = value.trim().split('.');
    if (parts.length != 4) return false;
    for (final part in parts) {
      if (part.isEmpty || !RegExp(r'^\d+$').hasMatch(part)) return false;
      final octet = int.tryParse(part);
      if (octet == null || octet < 0 || octet > 255) return false;
    }
    return true;
  }

  static String normalizeDnsServer(String text) => text.trim();

  static const String _dohPath = '/dns-query';

  static bool isValidDnsHostname(String value) {
    final token = value.trim();
    if (token.isEmpty || token.length > 253) return false;
    if (token.contains('://') ||
        token.contains('/') ||
        token.contains('?') ||
        token.contains('#') ||
        token.contains(':') ||
        token.startsWith('.') ||
        token.endsWith('.')) {
      return false;
    }
    final labels = token.split('.');
    if (labels.any((label) => label.isEmpty || label.length > 63)) {
      return false;
    }
    final labelPattern = RegExp(
      r'^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$',
    );
    return labels.every(labelPattern.hasMatch);
  }

  static String? _normalizedDoHEndpoint(String text) {
    final token = normalizeDnsServer(text);
    if (token.isEmpty) return null;
    final hasScheme = token.contains('://');
    final uri = Uri.tryParse(hasScheme ? token : 'https://$token');
    if (uri == null ||
        uri.host.isEmpty ||
        uri.fragment.isNotEmpty ||
        uri.userInfo.isNotEmpty) {
      return null;
    }
    if (uri.scheme.toLowerCase() != 'https') return null;
    final host = uri.host;
    if (isValidIpv4DnsServer(host) || !isValidDnsHostname(host)) {
      return null;
    }
    final hasExplicitTarget =
        hasScheme ||
        token.contains('/') ||
        token.contains('?') ||
        token.contains(':');
    final authority = uri.hasPort ? '$host:${uri.port}' : host;
    final path = uri.path.isEmpty
        ? (hasExplicitTarget ? '/' : _dohPath)
        : uri.path;
    final query = uri.hasQuery ? '?${uri.query}' : '';
    if (hasScheme) return 'https://$authority$path$query';
    if (hasExplicitTarget) return '$authority$path$query';
    return host;
  }

  static String normalizeDnsServerForProtocol(
    String text,
    DnsProtocol protocol,
  ) {
    final token = normalizeDnsServer(text);
    if (token.isEmpty) return '';
    if (protocol == DnsProtocol.dnsOverHttps) {
      return _normalizedDoHEndpoint(token) ?? token;
    }
    return token;
  }

  static String? invalidDnsServer(String text, DnsProtocol protocol) {
    final token = normalizeDnsServer(text);
    if (token.isEmpty) return null;
    if (protocol == DnsProtocol.dnsOverHttps) {
      return _normalizedDoHEndpoint(token) == null ? token : null;
    }
    if (protocol.requiresHostname) {
      return !isValidIpv4DnsServer(token) && isValidDnsHostname(token)
          ? null
          : token;
    }
    return isValidIpv4DnsServer(token) || isValidDnsHostname(token)
        ? null
        : token;
  }

  static String validationMessageForDnsServer(
    String label,
    String text,
    DnsProtocol protocol,
  ) {
    if (invalidDnsServer(text, protocol) == null) return '';
    final requirement = switch (protocol) {
      DnsProtocol.dnsOverTcp || DnsProtocol.dnsOverUdp => 'hostname or IPv4',
      DnsProtocol.dnsOverTls => 'hostname',
      DnsProtocol.dnsOverHttps => 'hostname or HTTPS URL',
    };
    return '$label: use $requirement';
  }

  static List<DnsServerConfig> orderedDnsServers({
    required String dns1Host,
    required DnsProtocol dns1Protocol,
    required String dns2Host,
    required DnsProtocol dns2Protocol,
  }) {
    final out = <String>[];
    final configs = <DnsServerConfig>[];
    // Preserve slot priority while dropping empty and duplicate entries.
    for (final entry in <DnsServerConfig>[
      DnsServerConfig(
        host: normalizeDnsServerForProtocol(dns1Host, dns1Protocol),
        protocol: dns1Protocol,
      ),
      DnsServerConfig(
        host: normalizeDnsServerForProtocol(dns2Host, dns2Protocol),
        protocol: dns2Protocol,
      ),
    ]) {
      if (entry.host.isEmpty) continue;
      final fingerprint = '${entry.protocol.jsonValue}:${entry.host}';
      if (out.contains(fingerprint)) continue;
      out.add(fingerprint);
      configs.add(entry);
    }
    return configs;
  }

  Map<String, dynamic> toJson() => {
    'id': id,
    'displayName': displayName,
    'server': server,
    'user': user,
    'dnsAutomatic': dnsAutomatic,
    'dns1Host': normalizeDnsServerForProtocol(dns1Host, dns1Protocol),
    'dns1Protocol': dns1Protocol.jsonValue,
    'dns2Host': normalizeDnsServerForProtocol(dns2Host, dns2Protocol),
    'dns2Protocol': dns2Protocol.jsonValue,
    'mtu': mtu,
  };

  static Profile? tryFromJson(Object? raw) {
    if (raw is! Map) return null;
    final m = Map<String, dynamic>.from(raw);
    final id = m['id'];
    final displayName = m['displayName'];
    final server = m['server'];
    final user = m['user'];
    final dnsAutomatic = m['dnsAutomatic'];
    final dns1Host = m['dns1Host'];
    final dns1Protocol = m['dns1Protocol'];
    final dns2Host = m['dns2Host'];
    final dns2Protocol = m['dns2Protocol'];
    if (id is! String ||
        displayName is! String ||
        server is! String ||
        user is! String ||
        dnsAutomatic is! bool ||
        dns1Host is! String ||
        dns2Host is! String ||
        dns1Protocol is! String ||
        dns2Protocol is! String) {
      return null;
    }
    if (id.isEmpty || server.isEmpty) return null;
    int mtu = defaultVpnMtu;
    final mtuRaw = m['mtu'];
    final parsedDns1Protocol = DnsProtocol.fromJson(dns1Protocol);
    final parsedDns2Protocol = DnsProtocol.fromJson(dns2Protocol);
    if (mtuRaw is int) {
      mtu = normalizeMtu(mtuRaw);
    } else if (mtuRaw is num) {
      mtu = normalizeMtu(mtuRaw.toInt());
    } else if (mtuRaw is String) {
      mtu = mtuFromText(mtuRaw, fallback: defaultVpnMtu);
    }
    return Profile(
      id: id,
      displayName: displayName,
      server: server,
      user: user,
      dnsAutomatic: dnsAutomatic,
      dns1Host: normalizeDnsServerForProtocol(dns1Host, parsedDns1Protocol),
      dns1Protocol: parsedDns1Protocol,
      dns2Host: normalizeDnsServerForProtocol(dns2Host, parsedDns2Protocol),
      dns2Protocol: parsedDns2Protocol,
      mtu: mtu,
    );
  }
}
