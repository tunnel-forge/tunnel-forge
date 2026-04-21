import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tunnel_forge/main.dart';
import 'package:tunnel_forge/profile_models.dart';
import 'package:tunnel_forge/profile_store.dart';
import 'package:tunnel_forge/profile_transfer.dart';
import 'package:tunnel_forge/profile_transfer_contract.dart';

import '../integration_test/support/vpn_channel_mock.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  Future<void> pumpApp(WidgetTester tester, ProfileStore store) async {
    await tester.pumpWidget(TunnelForgeApp(profileStore: store));
    await tester.pump();
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
  }

  Future<void> sendHostTransfer(
    WidgetTester tester, {
    required String type,
    required String data,
  }) async {
    const codec = StandardMethodCodec();
    final payload = codec.encodeMethodCall(
      MethodCall(ProfileTransferContract.onIncomingTransfer, {
        ProfileTransferContract.argType: type,
        ProfileTransferContract.argData: data,
      }),
    );
    await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
      ProfileTransferContract.channel,
      payload,
      (ByteData? _) {},
    );
    await tester.pump();
  }

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    installVpnChannelMock(<String>[]);
  });

  tearDown(uninstallVpnChannelMock);

  testWidgets('copy share link writes tf uri to clipboard', (tester) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.clear();
    final store = ProfileStore(
      prefsOverride: prefs,
      secretsOverride: MemorySecretStore(),
    );
    await store.upsertProfile(
      const Profile(
        id: 'profile-1',
        displayName: 'Office',
        server: 'vpn.example.com',
        user: 'alice',
        dnsAutomatic: false,
        dns1Host: '1.1.1.1',
        dns1Protocol: DnsProtocol.dnsOverUdp,
        dns2Host: '',
        dns2Protocol: DnsProtocol.dnsOverUdp,
      ),
      password: 'pw',
      psk: 'psk',
    );

    String? clipboardText;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel(ProfileTransferContract.channel),
          (call) async => <Object?>[],
        );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, (call) async {
          if (call.method == 'Clipboard.setData') {
            final args = Map<String, dynamic>.from(call.arguments as Map);
            clipboardText = args['text'] as String?;
          }
          return null;
        });
    addTearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            const MethodChannel(ProfileTransferContract.channel),
            null,
          );
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, null);
    });

    await pumpApp(tester, store);

    await tester.tap(find.byKey(const Key('profile_picker_tile')));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Profile actions'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Copy share link').last);
    await tester.pumpAndSettle();

    expect(clipboardText, startsWith('tf://p/'));
    expect(find.text('Share link copied'), findsOneWidget);
  });

  testWidgets('host transfer imports and selects profile', (tester) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.clear();
    final store = ProfileStore(
      prefsOverride: prefs,
      secretsOverride: MemorySecretStore(),
    );

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel(ProfileTransferContract.channel),
          (call) async => <Object?>[],
        );
    addTearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            const MethodChannel(ProfileTransferContract.channel),
            null,
          );
    });

    await pumpApp(tester, store);

    const envelope = ProfileTransferEnvelope(
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
    await sendHostTransfer(
      tester,
      type: ProfileTransferContract.typeTfpJson,
      data: envelope.toFileJson(),
    );
    await tester.pumpAndSettle();

    final profiles = await store.loadProfiles();
    expect(profiles, hasLength(1));
    final imported = profiles.single;
    final importedRow = await store.loadProfileWithSecrets(imported.id);
    expect(importedRow, isNotNull);
    expect(importedRow!.password, 'pw');
    expect(find.text('Imported "Office" from file'), findsOneWidget);
    expect(find.text('Office'), findsWidgets);
  });
}
