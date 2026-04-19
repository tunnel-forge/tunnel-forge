import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tunnel_forge/profile_models.dart';
import 'package:tunnel_forge/profile_store.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('Profile.tryFromJson', () {
    test('parses valid map', () {
      final p = Profile.tryFromJson({
        'id': 'a1',
        'displayName': 'Office',
        'server': 'vpn.company.net',
        'user': 'u',
        'dns': '1.1.1.1',
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
        'dns': '8.8.8.8',
        'mtu': 9000,
      });
      expect(hi, isNotNull);
      expect(hi!.mtu, Profile.maxVpnMtu);
      final lo = Profile.tryFromJson({
        'id': 'm2',
        'displayName': 'M',
        'server': 's',
        'user': 'u',
        'dns': '8.8.8.8',
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
        'dns': '8.8.8.8',
        'routingMode': 'perAppAllowList',
        'allowedAppPackages': ['com.foo.app', 'com.bar.app'],
      });
      expect(p, isNotNull);
      expect(p!.id, 'a2');
      expect(p.toJson().containsKey('routingMode'), isFalse);
      expect(p.toJson().containsKey('allowedAppPackages'), isFalse);
    });

    test('normalizes multi-dns entries while preserving order', () {
      expect(
        Profile.dnsServersFromText(' 1.1.1.1,8.8.8.8; 1.1.1.1 \n 9.9.9.9 '),
        ['1.1.1.1', '8.8.8.8', '9.9.9.9'],
      );
      expect(
        Profile.normalizeDns(' 1.1.1.1,8.8.8.8; 1.1.1.1 \n 9.9.9.9 '),
        '1.1.1.1, 8.8.8.8, 9.9.9.9',
      );
    });

    test('falls back to default dns when input is empty', () {
      expect(Profile.dnsServersFromText('   '), [Profile.defaultDns]);
      expect(Profile.normalizeDns(''), Profile.defaultDns);
    });

    test('reports the first invalid dns server token', () {
      expect(
        Profile.firstInvalidDnsServer('1.1.1.1, example.com, 9.9.9.9'),
        'example.com',
      );
      expect(Profile.firstInvalidDnsServer('1.1.1.1, 9.9.9.9'), isNull);
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
        dns: '8.8.8.8',
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
  });
}
