import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tunnel_forge/features/onboarding/domain/onboarding_repository.dart';
import 'package:tunnel_forge/main.dart';
import 'package:tunnel_forge/profile_models.dart';
import 'package:tunnel_forge/profile_store.dart';
import 'package:tunnel_forge/profile_transfer.dart';
import 'package:tunnel_forge/profile_transfer_bridge.dart';
import 'package:tunnel_forge/profile_transfer_contract.dart';
import 'package:tunnel_forge/vpn_contract.dart';

import 'support/host_to_dart_channel.dart';
import 'support/vpn_channel_mock.dart';

class _AcceptedOnboardingRepository implements OnboardingRepository {
  const _AcceptedOnboardingRepository();

  @override
  Future<int?> loadAcknowledgedVersion() async => kCurrentL2tpDisclosureVersion;

  @override
  Future<void> saveAcknowledgedVersion(int version) async {}
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  Future<void> pumpApp(
    WidgetTester tester,
    ProfileStore store, {
    ProfileTransferBridge? profileTransferBridge,
  }) async {
    await tester.pumpWidget(
      TunnelForgeApp(
        onboardingRepository: const _AcceptedOnboardingRepository(),
        profileStore: store,
        profileTransferBridge: profileTransferBridge,
      ),
    );
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
    expect(
      find.text('Imported profile "Office" from a .tfp file'),
      findsOneWidget,
    );
    expect(find.text('Office'), findsWidgets);
  });

  testWidgets('transfer emitted during bridge startup is not dropped', (
    tester,
  ) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.clear();
    final store = ProfileStore(
      prefsOverride: prefs,
      secretsOverride: MemorySecretStore(),
    );
    const envelope = ProfileTransferEnvelope(
      displayName: 'Startup Import',
      server: 'startup.example.com',
      user: 'alice',
      password: 'pw',
      psk: '',
      dnsAutomatic: true,
      dns1Host: '',
      dns1Protocol: DnsProtocol.dnsOverUdp,
      dns2Host: '',
      dns2Protocol: DnsProtocol.dnsOverUdp,
      mtu: 1400,
    );
    final bridge = TestProfileTransferBridge(
      onStart: (bridge) async {
        bridge.emit(
          IncomingProfileTransfer(
            type: ProfileTransferContract.typeTfpJson,
            data: envelope.toFileJson(),
            source: 'startup.tfp',
          ),
        );
        return const <IncomingProfileTransfer>[];
      },
    );

    await pumpApp(tester, store, profileTransferBridge: bridge);

    final profiles = await store.loadProfiles();
    expect(profiles, hasLength(1));
    expect(profiles.single.displayName, 'Startup Import');
    expect(
      find.text('Imported profile "Startup Import" from a .tfp file'),
      findsOneWidget,
    );
  });

  testWidgets('clipboard import accepts TunnelForge share links', (
    tester,
  ) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.clear();
    final store = ProfileStore(
      prefsOverride: prefs,
      secretsOverride: MemorySecretStore(),
    );

    const envelope = ProfileTransferEnvelope(
      displayName: 'Clipboard Link',
      server: 'clipboard-link.example.com',
      user: 'alice',
      password: 'pw',
      psk: '',
      dnsAutomatic: true,
      dns1Host: '',
      dns1Protocol: DnsProtocol.dnsOverUdp,
      dns2Host: '',
      dns2Protocol: DnsProtocol.dnsOverUdp,
      mtu: 1400,
    );

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, (call) async {
          if (call.method == 'Clipboard.getData') {
            return <String, Object?>{'text': envelope.toTfUri()};
          }
          return null;
        });
    addTearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, null);
    });

    await pumpApp(tester, store);

    await tester.tap(find.byKey(const Key('profile_picker_tile')));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Add profile'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Import from clipboard').last);
    await tester.pumpAndSettle();

    final profiles = await store.loadProfiles();
    expect(profiles, hasLength(1));
    expect(profiles.single.displayName, 'Clipboard Link');
    expect(
      find.text(
        'Imported profile "Clipboard Link" from a clipboard share link',
      ),
      findsOneWidget,
    );
  });

  testWidgets('clipboard import accepts raw profile json', (tester) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.clear();
    final store = ProfileStore(
      prefsOverride: prefs,
      secretsOverride: MemorySecretStore(),
    );

    const envelope = ProfileTransferEnvelope(
      displayName: 'Clipboard Json',
      server: 'clipboard-json.example.com',
      user: 'alice',
      password: 'pw',
      psk: '',
      dnsAutomatic: true,
      dns1Host: '',
      dns1Protocol: DnsProtocol.dnsOverUdp,
      dns2Host: '',
      dns2Protocol: DnsProtocol.dnsOverUdp,
      mtu: 1400,
    );

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, (call) async {
          if (call.method == 'Clipboard.getData') {
            return <String, Object?>{'text': envelope.toFileJson()};
          }
          return null;
        });
    addTearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, null);
    });

    await pumpApp(tester, store);

    await tester.tap(find.byKey(const Key('profile_picker_tile')));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Add profile'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Import from clipboard').last);
    await tester.pumpAndSettle();

    final profiles = await store.loadProfiles();
    expect(profiles, hasLength(1));
    expect(profiles.single.displayName, 'Clipboard Json');
    expect(
      find.text('Imported profile "Clipboard Json" from the clipboard'),
      findsOneWidget,
    );
  });

  testWidgets('host transfer while connected does not replace active profile', (
    tester,
  ) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.clear();
    final store = ProfileStore(
      prefsOverride: prefs,
      secretsOverride: MemorySecretStore(),
    );
    await store.upsertProfile(
      const Profile(
        id: 'active-profile',
        displayName: 'Primary',
        server: 'primary.example.com',
        user: 'alice',
      ),
      password: 'pw',
      psk: '',
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
    await simulateHostTunnelState(tester, VpnTunnelState.connected, 'tun0');
    await tester.pumpAndSettle();

    const envelope = ProfileTransferEnvelope(
      displayName: 'Imported',
      server: 'imported.example.com',
      user: 'bob',
      password: 'pw',
      psk: '',
      dnsAutomatic: true,
      dns1Host: '',
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

    expect(
      find.text('Imported profile "Imported" from a .tfp file'),
      findsOneWidget,
    );
    expect(find.text('Primary'), findsOneWidget);
    expect(find.text('primary.example.com'), findsOneWidget);
  });

  testWidgets(
    'host transfer while connecting does not replace active profile',
    (tester) async {
      final prefs = await SharedPreferences.getInstance();
      await prefs.clear();
      final store = ProfileStore(
        prefsOverride: prefs,
        secretsOverride: MemorySecretStore(),
      );
      await store.upsertProfile(
        const Profile(
          id: 'active-profile',
          displayName: 'Primary',
          server: 'primary.example.com',
          user: 'alice',
        ),
        password: 'pw',
        psk: '',
      );
      final bridge = TestProfileTransferBridge();

      await pumpApp(tester, store, profileTransferBridge: bridge);

      await tester.tap(find.byKey(const Key('vpn_connect')));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));

      bridge.emit(
        IncomingProfileTransfer(
          type: ProfileTransferContract.typeTfpJson,
          data: ProfileTransferEnvelope(
            displayName: 'Imported',
            server: 'imported.example.com',
            user: 'bob',
            password: 'pw',
            psk: '',
            dnsAutomatic: true,
            dns1Host: '',
            dns1Protocol: DnsProtocol.dnsOverUdp,
            dns2Host: '',
            dns2Protocol: DnsProtocol.dnsOverUdp,
            mtu: 1400,
          ).toFileJson(),
          source: 'imported.tfp',
        ),
      );
      for (var i = 0; i < 5; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }

      expect(
        find.text('Imported profile "Imported" from a .tfp file'),
        findsOneWidget,
      );
      expect(find.text('Primary'), findsOneWidget);
      expect(find.text('primary.example.com'), findsOneWidget);
    },
  );
}

class TestProfileTransferBridge extends ProfileTransferBridge {
  TestProfileTransferBridge({this.onStart});

  final StreamController<IncomingProfileTransfer> _incoming =
      StreamController<IncomingProfileTransfer>.broadcast();
  final Future<List<IncomingProfileTransfer>> Function(
    TestProfileTransferBridge bridge,
  )?
  onStart;

  @override
  Stream<IncomingProfileTransfer> get incomingTransfers => _incoming.stream;

  void emit(IncomingProfileTransfer transfer) {
    if (_incoming.isClosed) return;
    _incoming.add(transfer);
  }

  @override
  Future<List<IncomingProfileTransfer>> start() async {
    return onStart?.call(this) ?? const <IncomingProfileTransfer>[];
  }

  @override
  Future<void> dispose() => _incoming.close();
}
