import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tunnel_forge/profile_models.dart';
import 'package:tunnel_forge/profile_store.dart';
import 'package:tunnel_forge/profile_transfer.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('ProfileTransferEnvelope', () {
    test('round-trips through .tfp json', () {
      final envelope = ProfileTransferEnvelope(
        displayName: 'Office',
        server: 'vpn.example.com',
        user: 'alice',
        password: 'pw',
        psk: 'psk',
        dnsAutomatic: false,
        dns1Host: '1.1.1.1',
        dns1Protocol: DnsProtocol.dnsOverUdp,
        dns2Host: 'dns.example.com',
        dns2Protocol: DnsProtocol.dnsOverTls,
        mtu: 1400,
      );

      final decoded = ProfileTransferEnvelope.fromFileJson(
        envelope.toFileJson(),
      );

      expect(decoded.displayName, 'Office');
      expect(decoded.server, 'vpn.example.com');
      expect(decoded.password, 'pw');
      expect(decoded.psk, 'psk');
      expect(decoded.dnsAutomatic, isFalse);
      expect(decoded.dns1Host, '1.1.1.1');
      expect(decoded.dns1Protocol, DnsProtocol.dnsOverUdp);
      expect(decoded.dns2Host, 'dns.example.com');
      expect(decoded.dns2Protocol, DnsProtocol.dnsOverTls);
      expect(decoded.mtu, 1400);
    });

    test('round-trips through tf uri payload', () {
      final envelope = ProfileTransferEnvelope(
        displayName: 'Office',
        server: 'vpn.example.com',
        user: 'alice',
        password: 'pw',
        psk: 'psk',
        dnsAutomatic: false,
        dns1Host: '1.1.1.1',
        dns1Protocol: DnsProtocol.dnsOverUdp,
        dns2Host: '',
        dns2Protocol: DnsProtocol.dnsOverUdp,
        mtu: 1400,
      );

      final decoded = ProfileTransferEnvelope.fromTfUri(envelope.toTfUri());

      expect(decoded.displayName, 'Office');
      expect(decoded.server, 'vpn.example.com');
      expect(decoded.password, 'pw');
      expect(decoded.psk, 'psk');
    });

    test('rejects unsupported version', () {
      expect(
        () => ProfileTransferEnvelope.fromJsonMap({
          'v': 99,
          'displayName': 'Office',
          'server': 'vpn.example.com',
          'user': 'alice',
          'password': 'pw',
          'psk': 'psk',
          'dnsAutomatic': false,
          'dns1Host': '1.1.1.1',
          'dns1Protocol': 'dnsOverUdp',
          'dns2Host': '',
          'dns2Protocol': 'dnsOverUdp',
          'mtu': 1400,
        }),
        throwsFormatException,
      );
    });

    test('preserves dns-over-https endpoint path on import', () {
      final decoded = ProfileTransferEnvelope.fromJsonMap({
        'v': ProfileTransferEnvelope.currentVersion,
        'displayName': 'Office',
        'server': 'vpn.example.com',
        'user': 'alice',
        'password': 'pw',
        'psk': 'psk',
        'dnsAutomatic': false,
        'dns1Host': 'wikimedia-dns.org/dns-query',
        'dns1Protocol': 'dnsOverHttps',
        'dns2Host': '',
        'dns2Protocol': 'dnsOverUdp',
        'mtu': 1400,
      });

      expect(decoded.dns1Host, 'wikimedia-dns.org/dns-query');
      expect(decoded.dns1Protocol, DnsProtocol.dnsOverHttps);
    });
  });

  group('ProfileStore imported profiles', () {
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

    test('saveImportedProfile creates a new row and secrets', () async {
      const existing = Profile(
        id: 'existing',
        displayName: 'Office',
        server: 'vpn.example.com',
        user: 'alice',
        dnsAutomatic: false,
        dns1Host: '1.1.1.1',
        dns1Protocol: DnsProtocol.dnsOverUdp,
        dns2Host: '',
        dns2Protocol: DnsProtocol.dnsOverUdp,
      );
      await store.upsertProfile(existing, password: 'old', psk: 'old-psk');

      final imported = await store.saveImportedProfile(
        const ProfileTransferEnvelope(
          displayName: 'Office',
          server: 'vpn.example.com',
          user: 'alice',
          password: 'new',
          psk: 'new-psk',
          dnsAutomatic: false,
          dns1Host: '1.1.1.1',
          dns1Protocol: DnsProtocol.dnsOverUdp,
          dns2Host: '',
          dns2Protocol: DnsProtocol.dnsOverUdp,
          mtu: 1400,
        ),
      );

      final profiles = await store.loadProfiles();
      expect(profiles, hasLength(2));
      expect(imported.id, isNot('existing'));
      final importedRow = await store.loadProfileWithSecrets(imported.id);
      expect(importedRow, isNotNull);
      expect(importedRow!.password, 'new');
      expect(importedRow.psk, 'new-psk');
    });
  });
}
