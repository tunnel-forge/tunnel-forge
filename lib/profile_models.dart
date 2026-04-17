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
    required this.dns,
    this.mtu = defaultVpnMtu,
  });

  /// TUN interface MTU (bytes). Default fits common L2TP PPP MRU 1280 with 2-byte ACFC proto prefix.
  static const int defaultVpnMtu = 1278;

  static const int minVpnMtu = 576;
  static const int maxVpnMtu = 1500;

  final String id;
  final String displayName;
  final String server;
  final String user;
  final String dns;

  /// Android VpnService [Builder.setMtu]; clamped [minVpnMtu]–[maxVpnMtu].
  final int mtu;

  static int normalizeMtu(int value) => value.clamp(minVpnMtu, maxVpnMtu);

  /// Parses editor / free-form input; invalid or empty [text] returns [fallback].
  static int mtuFromText(String text, {int fallback = defaultVpnMtu}) {
    final v = int.tryParse(text.trim());
    if (v == null) return fallback;
    return normalizeMtu(v);
  }

  Map<String, dynamic> toJson() => {
        'id': id,
        'displayName': displayName,
        'server': server,
        'user': user,
        'dns': dns,
        'mtu': mtu,
      };

  static Profile? tryFromJson(Object? raw) {
    if (raw is! Map) return null;
    final m = Map<String, dynamic>.from(raw);
    final id = m['id'];
    final displayName = m['displayName'];
    final server = m['server'];
    final user = m['user'];
    final dns = m['dns'];
    if (id is! String || displayName is! String || server is! String || user is! String || dns is! String) {
      return null;
    }
    if (id.isEmpty || server.isEmpty) return null;
    int mtu = defaultVpnMtu;
    final mtuRaw = m['mtu'];
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
      dns: dns,
      mtu: mtu,
    );
  }
}
