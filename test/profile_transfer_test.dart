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
        dns: '1.1.1.1, 8.8.8.8',
        mtu: 1400,
      );

      final decoded = ProfileTransferEnvelope.fromFileJson(
        envelope.toFileJson(),
      );

      expect(decoded.displayName, 'Office');
      expect(decoded.server, 'vpn.example.com');
      expect(decoded.password, 'pw');
      expect(decoded.psk, 'psk');
      expect(decoded.dns, '1.1.1.1, 8.8.8.8');
      expect(decoded.mtu, 1400);
    });

    test('round-trips through tf uri payload', () {
      final envelope = ProfileTransferEnvelope(
        displayName: 'Office',
        server: 'vpn.example.com',
        user: 'alice',
        password: 'pw',
        psk: 'psk',
        dns: '1.1.1.1',
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
          'dns': '1.1.1.1',
          'mtu': 1400,
        }),
        throwsFormatException,
      );
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
        dns: '1.1.1.1',
      );
      await store.upsertProfile(existing, password: 'old', psk: 'old-psk');

      final imported = await store.saveImportedProfile(
        const ProfileTransferEnvelope(
          displayName: 'Office',
          server: 'vpn.example.com',
          user: 'alice',
          password: 'new',
          psk: 'new-psk',
          dns: '1.1.1.1',
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
