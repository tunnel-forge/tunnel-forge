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
        'wikimedia-dns.org/custom-path',
      );
      expect(
        Profile.invalidDnsServer(
          'https://wikimedia-dns.org/dns-query',
          DnsProtocol.dnsOverHttps,
        ),
        'https://wikimedia-dns.org/dns-query',
      );
    });

    test('normalizes dns-over-https host and fixed path inputs', () {
      expect(
        Profile.normalizeDnsServerForProtocol(
          ' wikimedia-dns.org/dns-query ',
          DnsProtocol.dnsOverHttps,
        ),
        'wikimedia-dns.org',
      );
      expect(
        Profile.normalizeDnsServerForProtocol(
          'wikimedia-dns.org',
          DnsProtocol.dnsOverHttps,
        ),
        'wikimedia-dns.org',
      );
      expect(
        Profile.validationMessageForDnsServer(
          'DNS 1',
          'bad/path',
          DnsProtocol.dnsOverUdp,
        ),
        'DNS 1 must be a hostname or IPv4 address for DNS-over-UDP',
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
          httpEnabled: false,
          httpPort: 18080,
          socksEnabled: true,
          socksPort: 11080,
        ),
      );

      expect(await store.loadConnectionMode(), ConnectionMode.proxyOnly);
      final proxy = await store.loadProxySettings();
      expect(proxy.httpEnabled, isFalse);
      expect(proxy.httpPort, 18080);
      expect(proxy.socksEnabled, isTrue);
      expect(proxy.socksPort, 11080);
    });

    test('persists global connectivity check settings', () async {
      expect(
        (await store.loadConnectivityCheckSettings()).url,
        ConnectivityCheckSettings.defaultUrl,
      );

      await store.saveConnectivityCheckSettings(
        const ConnectivityCheckSettings(url: 'https://example.com/health'),
      );

      expect(
        (await store.loadConnectivityCheckSettings()).url,
        'https://example.com/health',
      );
    });

    test('defaults log display level to error and persists updates', () async {
      expect(await store.loadLogDisplayLevel(), LogDisplayLevel.error);

      await store.saveLogDisplayLevel(LogDisplayLevel.debug);

      expect(await store.loadLogDisplayLevel(), LogDisplayLevel.debug);
    });
  });
}
