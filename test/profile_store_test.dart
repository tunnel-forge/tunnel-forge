import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tunnel_forge/profile_models.dart';
import 'package:tunnel_forge/profile_store.dart';
import 'package:tunnel_forge/utils/log_entry.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('Profile.tryFromJson', () {
    test('parses valid map', () {
      final p = Profile.tryFromJson({
        'id': 'a1',
        'displayName': 'Office',
        'server': 'vpn.company.net',
        'user': 'u',
        'dnsAutomatic': false,
        'dns1Host': '1.1.1.1',
        'dns1Protocol': 'dnsOverUdp',
        'dns2Host': '',
        'dns2Protocol': 'dnsOverUdp',
      });
      expect(p, isNotNull);
      expect(p!.id, 'a1');
      expect(p.displayName, 'Office');
      expect(p.server, 'vpn.company.net');
      expect(p.mtu, Profile.defaultVpnMtu);
      expect(p.toJson().containsKey('routingMode'), isFalse);
    });

    test('parses mtu and clamps', () {
      final hi = Profile.tryFromJson({
        'id': 'm1',
        'displayName': 'M',
        'server': 's',
        'user': 'u',
        'dnsAutomatic': true,
        'dns1Host': '',
        'dns1Protocol': 'dnsOverUdp',
        'dns2Host': '',
        'dns2Protocol': 'dnsOverUdp',
        'mtu': 9000,
      });
      expect(hi, isNotNull);
      expect(hi!.mtu, Profile.maxVpnMtu);
      final lo = Profile.tryFromJson({
        'id': 'm2',
        'displayName': 'M',
        'server': 's',
        'user': 'u',
        'dnsAutomatic': true,
        'dns1Host': '',
        'dns1Protocol': 'dnsOverUdp',
        'dns2Host': '',
        'dns2Protocol': 'dnsOverUdp',
        'mtu': 100,
      });
      expect(lo, isNotNull);
      expect(lo!.mtu, Profile.minVpnMtu);
    });

    test('ignores legacy routing keys in stored json', () {
      final p = Profile.tryFromJson({
        'id': 'a2',
        'displayName': 'Per',
        'server': 'vpn.example',
        'user': 'u',
        'dnsAutomatic': true,
        'dns1Host': '',
        'dns1Protocol': 'dnsOverUdp',
        'dns2Host': '',
        'dns2Protocol': 'dnsOverUdp',
        'routingMode': 'perAppAllowList',
        'allowedAppPackages': ['com.foo.app', 'com.bar.app'],
      });
      expect(p, isNotNull);
      expect(p!.id, 'a2');
      expect(p.toJson().containsKey('routingMode'), isFalse);
      expect(p.toJson().containsKey('allowedAppPackages'), isFalse);
    });

    test('orders manual dns with dns1 priority and dns2 fallback', () {
      final ordered = Profile.orderedDnsServers(
        dns1Host: ' 1.1.1.1 ',
        dns1Protocol: DnsProtocol.dnsOverUdp,
        dns2Host: 'resolver.example',
        dns2Protocol: DnsProtocol.dnsOverTls,
      );
      expect(ordered, hasLength(2));
      expect(ordered[0].host, '1.1.1.1');
      expect(ordered[0].protocol, DnsProtocol.dnsOverUdp);
      expect(ordered[1].host, 'resolver.example');
      expect(ordered[1].protocol, DnsProtocol.dnsOverTls);
    });

    test('normalizes individual dns inputs', () {
      expect(Profile.normalizeDnsServer(' 1.1.1.1 '), '1.1.1.1');
      expect(Profile.normalizeDnsServer(''), '');
    });

    test('reports invalid dns entries per field', () {
      expect(
        Profile.invalidDnsServer('1.1.1.1', DnsProtocol.dnsOverTls),
        '1.1.1.1',
      );
      expect(
        Profile.invalidDnsServer('example.com', DnsProtocol.dnsOverUdp),
        isNull,
      );
      expect(
        Profile.invalidDnsServer(
          'wikimedia-dns.org/dns-query',
          DnsProtocol.dnsOverHttps,
        ),
        isNull,
      );
      expect(
        Profile.invalidDnsServer(
          'wikimedia-dns.org/custom-path',
          DnsProtocol.dnsOverHttps,
        ),
        isNull,
      );
      expect(
        Profile.invalidDnsServer(
          'https://wikimedia-dns.org/dns-query',
          DnsProtocol.dnsOverHttps,
        ),
        isNull,
      );
    });

    test('normalizes dns-over-https host and url inputs', () {
      expect(
        Profile.normalizeDnsServerForProtocol(
          ' wikimedia-dns.org/dns-query ',
          DnsProtocol.dnsOverHttps,
        ),
        'wikimedia-dns.org/dns-query',
      );
      expect(
        Profile.normalizeDnsServerForProtocol(
          'wikimedia-dns.org',
          DnsProtocol.dnsOverHttps,
        ),
        'wikimedia-dns.org',
      );
      expect(
        Profile.normalizeDnsServerForProtocol(
          ' https://wikimedia-dns.org/custom-path?dns=1 ',
          DnsProtocol.dnsOverHttps,
        ),
        'https://wikimedia-dns.org/custom-path?dns=1',
      );
      expect(
        Profile.validationMessageForDnsServer(
          'DNS 1',
          'ftp://bad.example/dns-query',
          DnsProtocol.dnsOverHttps,
        ),
        'DNS 1: use hostname or HTTPS URL',
      );
    });
  });

  group('ProfileStore', () {
    late SharedPreferences prefs;
    late MemorySecretStore secrets;
    late ProfileStore store;

    setUp(() async {
      SharedPreferences.setMockInitialValues({});
      prefs = await SharedPreferences.getInstance();
      await prefs.clear();
      secrets = MemorySecretStore();
      store = ProfileStore(prefsOverride: prefs, secretsOverride: secrets);
    });

    test('upsert and load with secrets', () async {
      const profile = Profile(
        id: 'id-1',
        displayName: 'One',
        server: 's.example',
        user: 'alice',
        dnsAutomatic: true,
        dns1Host: '',
        dns1Protocol: DnsProtocol.dnsOverUdp,
        dns2Host: '',
        dns2Protocol: DnsProtocol.dnsOverUdp,
      );
      await store.upsertProfile(profile, password: 'pw1', psk: 'psk1');

      final list = await store.loadProfiles();
      expect(list, hasLength(1));
      expect(list.single.server, 's.example');

      final row = await store.loadProfileWithSecrets('id-1');
      expect(row, isNotNull);
      expect(row!.profile.user, 'alice');
      expect(row.password, 'pw1');
      expect(row.psk, 'psk1');
    });

    test('starts empty without seeding a default profile', () async {
      expect(await store.loadProfiles(), isEmpty);
      expect(await store.loadLastProfileId(), isNull);
    });

    test('persists connection mode and proxy settings', () async {
      await store.saveConnectionMode(ConnectionMode.proxyOnly);
      await store.saveProxySettings(
        const ProxySettings(
          httpPort: 18080,
          socksPort: 11080,
          allowLanConnections: true,
        ),
      );

      expect(await store.loadConnectionMode(), ConnectionMode.proxyOnly);
      final proxy = await store.loadProxySettings();
      expect(proxy.httpPort, 18080);
      expect(proxy.socksPort, 11080);
      expect(proxy.allowLanConnections, isTrue);
    });

    test('persists split-tunnel settings', () async {
      await store.saveSplitTunnelSettings(
        const SplitTunnelSettings(
          enabled: true,
          mode: SplitTunnelMode.exclusive,
          inclusivePackages: ['com.example.alpha'],
          exclusivePackages: [' com.example.beta ', 'com.example.beta'],
        ),
      );

      final settings = await store.loadSplitTunnelSettings();
      expect(settings.enabled, isTrue);
      expect(settings.mode, SplitTunnelMode.exclusive);
      expect(settings.inclusivePackages, ['com.example.alpha']);
      expect(settings.exclusivePackages, ['com.example.beta']);
    });

    test('persists global connectivity check settings', () async {
      final initial = await store.loadConnectivityCheckSettings();
      expect(initial.url, ConnectivityCheckSettings.defaultUrl);
      expect(initial.timeoutMs, ConnectivityCheckSettings.defaultTimeoutMs);

      await store.saveConnectivityCheckSettings(
        const ConnectivityCheckSettings(
          url: 'https://example.com/health',
          timeoutMs: 3200,
        ),
      );

      final saved = await store.loadConnectivityCheckSettings();
      expect(saved.url, 'https://example.com/health');
      expect(saved.timeoutMs, 3200);
    });

    test('defaults log display level to error and persists updates', () async {
      expect(await store.loadLogDisplayLevel(), LogDisplayLevel.error);

      await store.saveLogDisplayLevel(LogDisplayLevel.debug);

      expect(await store.loadLogDisplayLevel(), LogDisplayLevel.debug);
    });
  });
}
