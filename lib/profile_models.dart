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

/// Android routing: entire device vs. selected apps only ([RoutingMode.perAppAllowList]).
enum RoutingMode {
  fullTunnel('fullTunnel'),
  perAppAllowList('perAppAllowList');

  const RoutingMode(this.jsonValue);
  final String jsonValue;

  static RoutingMode fromJson(Object? raw) {
    if (raw is! String) return RoutingMode.fullTunnel;
    return switch (raw) {
      'perAppAllowList' => RoutingMode.perAppAllowList,
      _ => RoutingMode.fullTunnel,
    };
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

/// Global local-proxy settings; ports are loopback-only in v1.
class ProxySettings {
  const ProxySettings({
    this.httpEnabled = true,
    this.httpPort = defaultHttpPort,
    this.socksEnabled = true,
    this.socksPort = defaultSocksPort,
  });

  static const int defaultHttpPort = 8080;
  static const int defaultSocksPort = 1080;
  static const int minPort = 1;
  static const int maxPort = 65535;

  final bool httpEnabled;
  final int httpPort;
  final bool socksEnabled;
  final int socksPort;

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
    bool? httpEnabled,
    int? httpPort,
    bool? socksEnabled,
    int? socksPort,
  }) {
    return ProxySettings(
      httpEnabled: httpEnabled ?? this.httpEnabled,
      httpPort: httpPort ?? this.httpPort,
      socksEnabled: socksEnabled ?? this.socksEnabled,
      socksPort: socksPort ?? this.socksPort,
    );
  }
}

/// Global endpoint used by the connectivity badge for direct reachability checks.
class ConnectivityCheckSettings {
  const ConnectivityCheckSettings({this.url = defaultUrl});

  static const String defaultUrl = 'http://www.gstatic.com/generate_204';

  final String url;

  static String normalizeUrl(String text) {
    final trimmed = text.trim();
    return trimmed.isEmpty ? defaultUrl : trimmed;
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

  ConnectivityCheckSettings copyWith({String? url}) {
    return ConnectivityCheckSettings(url: normalizeUrl(url ?? this.url));
  }
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

  static String? _normalizedDoHHostname(String text) {
    final token = normalizeDnsServer(text);
    if (token.isEmpty) return null;
    if (token.contains('://') ||
        token.contains('?') ||
        token.contains('#') ||
        token.contains(':')) {
      return null;
    }
    final slash = token.indexOf('/');
    final host = slash < 0 ? token : token.substring(0, slash);
    final path = slash < 0 ? '' : token.substring(slash);
    if (path.isNotEmpty && path != _dohPath) return null;
    if (host.isEmpty ||
        isValidIpv4DnsServer(host) ||
        !isValidDnsHostname(host)) {
      return null;
    }
    return host;
  }

  static String normalizeDnsServerForProtocol(
    String text,
    DnsProtocol protocol,
  ) {
    final token = normalizeDnsServer(text);
    if (token.isEmpty) return '';
    if (protocol == DnsProtocol.dnsOverHttps) {
      return _normalizedDoHHostname(token) ?? token;
    }
    return token;
  }

  static String? invalidDnsServer(String text, DnsProtocol protocol) {
    final token = normalizeDnsServer(text);
    if (token.isEmpty) return null;
    if (protocol == DnsProtocol.dnsOverHttps) {
      return _normalizedDoHHostname(token) == null ? token : null;
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
      DnsProtocol.dnsOverTcp ||
      DnsProtocol.dnsOverUdp => 'a hostname or IPv4 address',
      DnsProtocol.dnsOverTls || DnsProtocol.dnsOverHttps => 'a hostname',
    };
    return '$label must be $requirement for ${protocol.displayLabel}';
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
