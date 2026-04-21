import 'dart:convert';
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:tunnel_forge/connectivity_checker.dart';
import 'package:tunnel_forge/main.dart';
import 'package:tunnel_forge/profile_models.dart';
import 'package:tunnel_forge/profile_store.dart';
import 'package:tunnel_forge/theme.dart';
import 'package:tunnel_forge/vpn_contract.dart';
import 'package:tunnel_forge/widgets/connection_panel.dart';

import '../integration_test/support/host_to_dart_channel.dart';
import '../integration_test/support/vpn_channel_mock.dart';

class FakeConnectivityChecker implements ConnectivityChecker {
  final List<String> urls = <String>[];
  ConnectivityPingResult nextResult = ConnectivityPingResult.success(
    latencyMs: 84,
    statusCode: 204,
  );
  Completer<ConnectivityPingResult>? completer;

  @override
  Future<ConnectivityPingResult> ping(String url) {
    urls.add(url);
    final pending = completer;
    if (pending != null) return pending.future;
    return Future<ConnectivityPingResult>.value(nextResult);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  String statusText(WidgetTester tester) {
    return tester.widget<Text>(find.byKey(const Key('vpn_status'))).data ?? '';
  }

  String connectivityStatusText(WidgetTester tester) {
    return tester
            .widget<Text>(find.byKey(const Key('connectivity_status')))
            .data ??
        '';
  }

  Future<void> pumpConnectionPanel(
    WidgetTester tester, {
    Brightness brightness = Brightness.light,
    bool busy = false,
    bool tunnelUp = false,
    bool awaitingTunnel = false,
    bool canStartConnection = true,
    String label = 'Ready',
    ConnectivityBadgeState connectivityBadgeState = ConnectivityBadgeState.idle,
    String connectivityBadgeLabel = 'Tap to check',
    VoidCallback? onUnavailablePrimaryTap,
    VoidCallback? onConnectivityTap,
  }) async {
    final theme = appTheme(brightness);
    await tester.pumpWidget(
      MaterialApp(
        home: Theme(
          data: theme,
          child: Scaffold(
            body: ConnectionPanel(
              profilesLoading: false,
              profileSummaryTitle: 'Test',
              profileSummarySubtitle: 'vpn.test.example',
              onOpenProfilePicker: () {},
              busy: busy,
              tunnelUp: tunnelUp,
              awaitingTunnel: awaitingTunnel,
              canStartConnection: canStartConnection,
              connectButtonLabel: label,
              onPrimary: () {},
              onUnavailablePrimaryTap: onUnavailablePrimaryTap ?? () {},
              connectivityBadgeState: connectivityBadgeState,
              connectivityBadgeLabel: connectivityBadgeLabel,
              onConnectivityTap: onConnectivityTap ?? () {},
              colorScheme: theme.colorScheme,
              textTheme: theme.textTheme,
            ),
          ),
        ),
      ),
    );
  }

  Color? buttonBackground(WidgetTester tester, {bool disabled = false}) {
    final button = tester.widget<FilledButton>(
      find.byKey(const Key('vpn_connect')),
    );
    return button.style?.backgroundColor?.resolve(
      disabled ? {WidgetState.disabled} : <WidgetState>{},
    );
  }

  Color? actionRingColor(WidgetTester tester) {
    final container = tester.widget<Container>(
      find.byKey(const Key('vpn_action_ring')),
    );
    final decoration = container.decoration as BoxDecoration?;
    final border = decoration?.border as Border?;
    return border?.top.color;
  }

  test('appTheme uses neutral surfaces in light and dark modes', () {
    final light = appTheme(Brightness.light);
    final dark = appTheme(Brightness.dark);

    expect(light.colorScheme.surface, AppPalette.lightSurface);
    expect(
      light.colorScheme.surfaceContainerHighest,
      AppPalette.lightSurfaceHighest,
    );
    expect(
      light.inputDecorationTheme.fillColor,
      AppPalette.lightSurfaceContainer,
    );
    expect(light.colorScheme.primary, AppPalette.lightConnectIdle);

    expect(dark.colorScheme.surface, AppPalette.darkSurface);
    expect(
      dark.colorScheme.surfaceContainerHighest,
      AppPalette.darkSurfaceHighest,
    );
    expect(
      dark.inputDecorationTheme.fillColor,
      AppPalette.darkSurfaceContainer,
    );
    expect(dark.colorScheme.primary, AppPalette.darkConnectIdle);
  });

  testWidgets('ConnectionPanel tolerates zero and tiny heights', (
    WidgetTester tester,
  ) async {
    final theme = ThemeData();

    Future<void> pumpPanel(double height) async {
      await tester.pumpWidget(
        MaterialApp(
          theme: theme,
          home: Scaffold(
            body: Align(
              alignment: Alignment.topCenter,
              child: SizedBox(
                height: height,
                child: ConnectionPanel(
                  profilesLoading: false,
                  profileSummaryTitle: 'Test',
                  profileSummarySubtitle: 'vpn.test.example',
                  onOpenProfilePicker: () {},
                  busy: false,
                  tunnelUp: false,
                  awaitingTunnel: false,
                  canStartConnection: true,
                  connectButtonLabel: 'Ready',
                  onPrimary: () {},
                  onUnavailablePrimaryTap: () {},
                  connectivityBadgeState: ConnectivityBadgeState.idle,
                  connectivityBadgeLabel: 'Tap to check',
                  onConnectivityTap: () {},
                  colorScheme: theme.colorScheme,
                  textTheme: theme.textTheme,
                ),
              ),
            ),
          ),
        ),
      );

      expect(tester.takeException(), isNull);
    }

    await pumpPanel(0);
    await pumpPanel(24);
  });

  testWidgets(
    'ConnectionPanel uses neutral idle button colors in light and dark themes',
    (WidgetTester tester) async {
      await pumpConnectionPanel(tester);
      final lightStatus = tester.widget<Text>(
        find.byKey(const Key('vpn_status')),
      );
      expect(buttonBackground(tester), AppPalette.lightConnectIdle);
      expect(lightStatus.style?.color, AppPalette.lightOnSurfaceVariant);
      expect(find.text('Ready'), findsOneWidget);

      await pumpConnectionPanel(tester, brightness: Brightness.dark);
      final darkStatus = tester.widget<Text>(
        find.byKey(const Key('vpn_status')),
      );
      expect(buttonBackground(tester), AppPalette.darkConnectIdle);
      expect(darkStatus.style?.color, AppPalette.darkOnSurfaceVariant);
      expect(find.text('Ready'), findsOneWidget);
    },
  );

  testWidgets(
    'ConnectionPanel uses amber progress and green connected status',
    (WidgetTester tester) async {
      await pumpConnectionPanel(tester, busy: true, label: 'Connecting...');

      final progress = tester.widget<CircularProgressIndicator>(
        find.byType(CircularProgressIndicator),
      );
      final connectingStatus = tester.widget<Text>(
        find.byKey(const Key('vpn_status')),
      );
      expect(
        buttonBackground(tester, disabled: true),
        AppPalette.lightSurfaceHighest,
      );
      expect(progress.color, AppPalette.lightConnecting);
      expect(connectingStatus.style?.color, AppPalette.lightConnecting);
      expect(find.text('Connecting...'), findsOneWidget);

      await pumpConnectionPanel(tester, tunnelUp: true, label: 'Connected');

      final connectedStatus = tester.widget<Text>(
        find.byKey(const Key('vpn_status')),
      );
      expect(buttonBackground(tester), AppPalette.lightDisconnect);
      expect(
        actionRingColor(tester),
        AppPalette.lightDisconnect.withValues(alpha: 0.32),
      );
      expect(connectedStatus.style?.color, AppPalette.lightConnected);
      expect(find.text('Connected'), findsOneWidget);
    },
  );

  testWidgets('ConnectionPanel shows compact connectivity badge states', (
    WidgetTester tester,
  ) async {
    await pumpConnectionPanel(tester);
    expect(find.byKey(const Key('connectivity_status')), findsNothing);
    expect(find.byKey(const Key('connectivity_status_spinner')), findsNothing);
    expect(find.byKey(const Key('connectivity_status_dot')), findsNothing);

    await pumpConnectionPanel(
      tester,
      tunnelUp: true,
      connectivityBadgeState: ConnectivityBadgeState.checking,
      connectivityBadgeLabel: 'Checking...',
    );
    expect(connectivityStatusText(tester), 'Checking...');
    expect(
      find.byKey(const Key('connectivity_status_spinner')),
      findsOneWidget,
    );

    await pumpConnectionPanel(
      tester,
      tunnelUp: true,
      connectivityBadgeState: ConnectivityBadgeState.success,
      connectivityBadgeLabel: '84 ms',
    );
    expect(connectivityStatusText(tester), '84 ms');

    await pumpConnectionPanel(
      tester,
      tunnelUp: true,
      connectivityBadgeState: ConnectivityBadgeState.failure,
      connectivityBadgeLabel: 'Unreachable',
    );
    expect(connectivityStatusText(tester), 'Unreachable');
  });

  testWidgets('ConnectionPanel disables connect when no active profile', (
    WidgetTester tester,
  ) async {
    var disabledTapCount = 0;
    await pumpConnectionPanel(
      tester,
      canStartConnection: false,
      label: 'Select profile',
      onUnavailablePrimaryTap: () => disabledTapCount++,
    );

    final button = tester.widget<FilledButton>(
      find.byKey(const Key('vpn_connect')),
    );
    expect(button.onPressed, isNull);
    expect(
      buttonBackground(tester, disabled: true),
      AppPalette.lightSurfaceHighest,
    );
    expect(statusText(tester), 'Select profile');

    await tester.tap(find.byKey(const Key('vpn_connect_disabled_tap_target')));
    await tester.pump();

    expect(disabledTapCount, 1);
  });

  testWidgets('Connect runs prepare then connect; awaits TUN status', (
    WidgetTester tester,
  ) async {
    SharedPreferences.setMockInitialValues({
      ProfileStore.prefsKeyProfilesJson: jsonEncode([
        {
          'id': 'widget_test_p1',
          'displayName': 'Test',
          'server': 'vpn.test.example',
          'user': '',
          'dnsAutomatic': true,
          'dns1Host': '',
          'dns1Protocol': 'dnsOverUdp',
          'dns2Host': '',
          'dns2Protocol': 'dnsOverUdp',
        },
      ]),
      ProfileStore.prefsKeyLastProfileId: 'widget_test_p1',
    });
    final methods = <String>[];
    installVpnChannelMock(methods);
    addTearDown(uninstallVpnChannelMock);
    addTearDown(() async {
      await tester.binding.setSurfaceSize(null);
    });
    await tester.binding.setSurfaceSize(const Size(480, 1200));

    await tester.pumpWidget(
      TunnelForgeApp(
        profileStore: ProfileStore(secretsOverride: MemorySecretStore()),
      ),
    );

    await tester.pump();
    for (var i = 0; i < 50; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }

    expect(find.byKey(const Key('vpn_status')), findsOneWidget);
    expect(find.textContaining('vpn.test.example'), findsWidgets);

    await tester.tap(find.byKey(const Key('vpn_connect')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    expect(methods, [
      VpnContract.setLogLevel,
      VpnContract.prepareVpn,
      VpnContract.connect,
    ]);
    expect(statusText(tester), contains('Connecting'));

    // Mock never delivers TUN; UI clears "Connecting..." after the 60s await timeout.
    await tester.pump(const Duration(seconds: 60));
    await tester.pump();

    expect(statusText(tester), 'Ready');
  });

  testWidgets('tapping merged status badge while connected updates latency', (
    WidgetTester tester,
  ) async {
    SharedPreferences.setMockInitialValues({});
    final methods = <String>[];
    final checker = FakeConnectivityChecker();
    installVpnChannelMock(methods);
    addTearDown(uninstallVpnChannelMock);
    addTearDown(() async {
      await tester.binding.setSurfaceSize(null);
    });
    await tester.binding.setSurfaceSize(const Size(480, 1200));

    await tester.pumpWidget(
      TunnelForgeApp(
        profileStore: ProfileStore(secretsOverride: MemorySecretStore()),
        connectivityChecker: checker,
      ),
    );

    await tester.pump();
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }

    expect(find.byKey(const Key('connectivity_status')), findsNothing);

    await simulateHostTunnelState(tester, VpnTunnelState.connected, 'tun0');
    await tester.pump();

    expect(connectivityStatusText(tester), '84 ms');
    checker.completer = Completer<ConnectivityPingResult>();

    await tester.tap(find.byKey(const Key('vpn_status_badge')));
    await tester.pump();

    expect(connectivityStatusText(tester), 'Checking...');
    expect(
      find.byKey(const Key('connectivity_status_spinner')),
      findsOneWidget,
    );
    expect(checker.urls, [
      ConnectivityCheckSettings.defaultUrl,
      ConnectivityCheckSettings.defaultUrl,
    ]);

    checker.completer!.complete(
      ConnectivityPingResult.success(latencyMs: 91, statusCode: 204),
    );
    await tester.pump();
    await tester.pump();

    expect(connectivityStatusText(tester), '91 ms');
  });

  testWidgets('successful connect auto-runs connectivity check once', (
    WidgetTester tester,
  ) async {
    SharedPreferences.setMockInitialValues({
      ProfileStore.prefsKeyProfilesJson: jsonEncode([
        {
          'id': 'widget_test_connectivity',
          'displayName': 'Connectivity Test',
          'server': 'vpn.test.example',
          'user': '',
          'dnsAutomatic': true,
          'dns1Host': '',
          'dns1Protocol': 'dnsOverUdp',
          'dns2Host': '',
          'dns2Protocol': 'dnsOverUdp',
        },
      ]),
      ProfileStore.prefsKeyLastProfileId: 'widget_test_connectivity',
    });
    final methods = <String>[];
    final checker = FakeConnectivityChecker();
    installVpnChannelMock(methods);
    addTearDown(uninstallVpnChannelMock);
    addTearDown(() async {
      await tester.binding.setSurfaceSize(null);
    });
    await tester.binding.setSurfaceSize(const Size(480, 1200));

    await tester.pumpWidget(
      TunnelForgeApp(
        profileStore: ProfileStore(secretsOverride: MemorySecretStore()),
        connectivityChecker: checker,
      ),
    );

    await tester.pump();
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }

    await tester.tap(find.byKey(const Key('vpn_connect')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));
    await simulateHostTunnelState(tester, VpnTunnelState.connected, 'tun0');
    await tester.pump();

    expect(checker.urls, [ConnectivityCheckSettings.defaultUrl]);
    expect(connectivityStatusText(tester), '84 ms');
  });

  testWidgets('logs default to error level and use cumulative filtering', (
    WidgetTester tester,
  ) async {
    SharedPreferences.setMockInitialValues({});
    final methods = <String>[];
    installVpnChannelMock(methods);
    addTearDown(uninstallVpnChannelMock);
    addTearDown(() async {
      await tester.binding.setSurfaceSize(null);
    });
    await tester.binding.setSurfaceSize(const Size(480, 1200));

    await tester.pumpWidget(
      TunnelForgeApp(
        profileStore: ProfileStore(secretsOverride: MemorySecretStore()),
      ),
    );

    await tester.pump();
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }

    await simulateHostEngineLog(
      tester,
      priority: 4,
      source: 'kotlin',
      tag: 'MainActivity',
      message: 'info message',
    );
    await simulateHostEngineLog(
      tester,
      priority: 5,
      source: 'native',
      tag: 'tunnel_engine',
      message: 'warn message',
    );
    await simulateHostEngineLog(
      tester,
      priority: 6,
      source: 'kotlin',
      tag: 'TunnelVpnService',
      message: 'error message',
    );
    await simulateHostEngineLog(
      tester,
      priority: 3,
      source: 'kotlin',
      tag: 'MainActivity',
      message: 'debug message',
    );

    await tester.tap(find.text('Logs'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    expect(find.textContaining('info message'), findsNothing);
    expect(find.textContaining('warn message'), findsNothing);
    expect(find.textContaining('error message'), findsOneWidget);
    expect(find.textContaining('debug message'), findsNothing);
    expect(find.text('ERROR'), findsOneWidget);

    await tester.tap(find.byTooltip('Log level'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('WARNING').last);
    await tester.pumpAndSettle();

    expect(find.textContaining('info message'), findsNothing);
    expect(find.textContaining('warn message'), findsOneWidget);
    expect(find.textContaining('error message'), findsOneWidget);
    expect(find.textContaining('debug message'), findsNothing);
    expect(find.text('Log level: WARNING'), findsOneWidget);
  });

  testWidgets('log sink redacts sensitive host fields before display', (
    WidgetTester tester,
  ) async {
    SharedPreferences.setMockInitialValues({});
    final methods = <String>[];
    installVpnChannelMock(methods);
    addTearDown(uninstallVpnChannelMock);
    addTearDown(() async {
      await tester.binding.setSurfaceSize(null);
    });
    await tester.binding.setSurfaceSize(const Size(480, 1200));

    await tester.pumpWidget(
      TunnelForgeApp(
        profileStore: ProfileStore(secretsOverride: MemorySecretStore()),
      ),
    );

    await tester.pump();
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }

    await simulateHostEngineLog(
      tester,
      priority: 6,
      source: 'kotlin',
      tag: 'TunnelVpnService',
      message:
          'connect server=vpn.example.com password=hunter2 target=secure.example.net:443 uri=https://example.com/token dns=a.example[UDP],b.example[TLS] expected=12345',
    );

    await tester.tap(find.text('Logs'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    expect(find.textContaining('vpn.example.com'), findsNothing);
    expect(find.textContaining('hunter2'), findsNothing);
    expect(find.textContaining('secure.example.net'), findsNothing);
    expect(find.textContaining('https://example.com/token'), findsNothing);
    expect(find.textContaining('a.example'), findsNothing);
    expect(find.textContaining('b.example'), findsNothing);
    expect(find.textContaining('server=[REDACTED_HOST]'), findsOneWidget);
    expect(find.textContaining('password=[REDACTED]'), findsOneWidget);
    expect(find.textContaining('target=[REDACTED_TARGET]:443'), findsOneWidget);
    expect(find.textContaining('uri=[REDACTED_URI]'), findsOneWidget);
    expect(find.textContaining('dns=[REDACTED_HOST]'), findsOneWidget);
    expect(find.textContaining('expected=12345'), findsOneWidget);
  });

  testWidgets('debug log level requires confirmation before enabling', (
    WidgetTester tester,
  ) async {
    SharedPreferences.setMockInitialValues({});
    final methods = <String>[];
    installVpnChannelMock(methods);
    addTearDown(uninstallVpnChannelMock);
    addTearDown(() async {
      await tester.binding.setSurfaceSize(null);
    });
    await tester.binding.setSurfaceSize(const Size(480, 1200));

    await tester.pumpWidget(
      TunnelForgeApp(
        profileStore: ProfileStore(secretsOverride: MemorySecretStore()),
      ),
    );

    await tester.pump();
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }

    await tester.tap(find.text('Logs'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    await tester.tap(find.byTooltip('Log level'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('DEBUG').last);
    await tester.pumpAndSettle();

    expect(find.text('Enable debug logs?'), findsOneWidget);
    expect(
      find.textContaining('may slow down the tunnel and the device'),
      findsOneWidget,
    );

    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();

    expect(find.text('Enable debug logs?'), findsNothing);
    expect(find.text('ERROR'), findsOneWidget);
    expect(methods.where((method) => method == VpnContract.setLogLevel), [
      VpnContract.setLogLevel,
    ]);

    await tester.tap(find.byTooltip('Log level'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('DEBUG').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Enable Debug'));
    await tester.pumpAndSettle();

    expect(find.text('DEBUG'), findsOneWidget);
    expect(methods.where((method) => method == VpnContract.setLogLevel), [
      VpnContract.setLogLevel,
      VpnContract.setLogLevel,
    ]);
  });

  testWidgets('copy visible only uses the current log level', (
    WidgetTester tester,
  ) async {
    SharedPreferences.setMockInitialValues({});
    String? clipboardText;
    installVpnChannelMock(<String>[]);
    addTearDown(uninstallVpnChannelMock);
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
          .setMockMethodCallHandler(SystemChannels.platform, null);
    });
    addTearDown(() async {
      await tester.binding.setSurfaceSize(null);
    });
    await tester.binding.setSurfaceSize(const Size(480, 1200));

    await tester.pumpWidget(
      TunnelForgeApp(
        profileStore: ProfileStore(secretsOverride: MemorySecretStore()),
      ),
    );

    await tester.pump();
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }

    await simulateHostEngineLog(
      tester,
      priority: 4,
      source: 'kotlin',
      tag: 'MainActivity',
      message: 'info message',
    );
    await simulateHostEngineLog(
      tester,
      priority: 5,
      source: 'native',
      tag: 'tunnel_engine',
      message: 'warn message',
    );
    await simulateHostEngineLog(
      tester,
      priority: 6,
      source: 'kotlin',
      tag: 'TunnelVpnService',
      message: 'error message',
    );

    await tester.tap(find.text('Logs'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    await tester.tap(find.byTooltip('Log level'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('WARNING').last);
    await tester.pumpAndSettle();

    await tester.tap(find.byTooltip('Copy visible'));
    await tester.pump();

    expect(clipboardText, isNot(contains('info message')));
    expect(clipboardText, contains('warn message'));
    expect(clipboardText, contains('error message'));
  });

  testWidgets('VPN tab shows profile picker tile', (WidgetTester tester) async {
    SharedPreferences.setMockInitialValues({
      ProfileStore.prefsKeyProfilesJson: jsonEncode([
        {
          'id': 'widget_test_p2',
          'displayName': 'Test',
          'server': 'vpn.test.example',
          'user': '',
          'dnsAutomatic': true,
          'dns1Host': '',
          'dns1Protocol': 'dnsOverUdp',
          'dns2Host': '',
          'dns2Protocol': 'dnsOverUdp',
        },
      ]),
      ProfileStore.prefsKeyLastProfileId: 'widget_test_p2',
    });
    final methods = <String>[];
    installVpnChannelMock(methods);
    addTearDown(uninstallVpnChannelMock);
    addTearDown(() async {
      await tester.binding.setSurfaceSize(null);
    });
    await tester.binding.setSurfaceSize(const Size(480, 1200));

    await tester.pumpWidget(
      TunnelForgeApp(
        profileStore: ProfileStore(secretsOverride: MemorySecretStore()),
      ),
    );

    await tester.pump();
    for (var i = 0; i < 50; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }

    expect(find.byKey(const Key('profile_picker_tile')), findsOneWidget);
  });

  testWidgets('cold start with empty prefs shows no saved profile state', (
    WidgetTester tester,
  ) async {
    SharedPreferences.setMockInitialValues({});
    final methods = <String>[];
    installVpnChannelMock(methods);
    addTearDown(uninstallVpnChannelMock);
    addTearDown(() async {
      await tester.binding.setSurfaceSize(null);
    });
    await tester.binding.setSurfaceSize(const Size(480, 1200));

    await tester.pumpWidget(
      TunnelForgeApp(
        profileStore: ProfileStore(secretsOverride: MemorySecretStore()),
      ),
    );

    await tester.pump();
    for (var i = 0; i < 50; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }

    expect(find.text('No saved profile'), findsOneWidget);
    expect(find.text('Create your first profile'), findsOneWidget);
    expect(statusText(tester), 'Select profile');
    final button = tester.widget<FilledButton>(
      find.byKey(const Key('vpn_connect')),
    );
    expect(button.onPressed, isNull);
    expect(
      find.byKey(const Key('vpn_connect_disabled_tap_target')),
      findsOneWidget,
    );

    await tester.tap(find.byKey(const Key('vpn_connect_disabled_tap_target')));
    await tester.pump();

    expect(
      find.text(
        'Create a profile first. You will need a server, username, and tunnel settings before connecting.',
      ),
      findsOneWidget,
    );
  });

  testWidgets('proxy-only skips VPN prepare and sends connect', (
    WidgetTester tester,
  ) async {
    SharedPreferences.setMockInitialValues({
      ProfileStore.prefsKeyProfilesJson: jsonEncode([
        {
          'id': 'widget_test_proxy',
          'displayName': 'Proxy Test',
          'server': 'vpn.test.example',
          'user': '',
          'dnsAutomatic': true,
          'dns1Host': '',
          'dns1Protocol': 'dnsOverUdp',
          'dns2Host': '',
          'dns2Protocol': 'dnsOverUdp',
        },
      ]),
      ProfileStore.prefsKeyLastProfileId: 'widget_test_proxy',
      ProfileStore.prefsKeyConnectionMode: 'proxyOnly',
    });
    final methods = <String>[];
    installVpnChannelMock(methods);
    addTearDown(uninstallVpnChannelMock);
    addTearDown(() async {
      await tester.binding.setSurfaceSize(null);
    });
    await tester.binding.setSurfaceSize(const Size(480, 1200));

    await tester.pumpWidget(
      TunnelForgeApp(
        profileStore: ProfileStore(secretsOverride: MemorySecretStore()),
      ),
    );

    await tester.pump();
    for (var i = 0; i < 50; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }

    await tester.tap(find.byKey(const Key('vpn_connect')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    expect(methods, [VpnContract.setLogLevel, VpnContract.connect]);
    expect(statusText(tester), contains('Connecting'));
  });

  testWidgets('automatic DNS ignores stale invalid manual DNS values', (
    WidgetTester tester,
  ) async {
    SharedPreferences.setMockInitialValues({
      ProfileStore.prefsKeyProfilesJson: jsonEncode([
        {
          'id': 'widget_test_auto_dns',
          'displayName': 'Automatic DNS',
          'server': 'vpn.test.example',
          'user': '',
          'dnsAutomatic': true,
          'dns1Host': 'bad/path',
          'dns1Protocol': 'dnsOverUdp',
          'dns2Host': '1.1.1.1',
          'dns2Protocol': 'dnsOverHttps',
        },
      ]),
      ProfileStore.prefsKeyLastProfileId: 'widget_test_auto_dns',
    });
    final methods = <String>[];
    MethodCall? connectCall;
    installVpnChannelMock(
      methods,
      onConnect: (call) async {
        connectCall = call;
        return null;
      },
    );
    addTearDown(uninstallVpnChannelMock);
    addTearDown(() async {
      await tester.binding.setSurfaceSize(null);
    });
    await tester.binding.setSurfaceSize(const Size(480, 1200));

    await tester.pumpWidget(
      TunnelForgeApp(
        profileStore: ProfileStore(secretsOverride: MemorySecretStore()),
      ),
    );

    await tester.pump();
    for (var i = 0; i < 50; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }

    await tester.tap(find.byKey(const Key('vpn_connect')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    expect(methods, [
      VpnContract.setLogLevel,
      VpnContract.prepareVpn,
      VpnContract.connect,
    ]);
    expect(connectCall, isNotNull);
    final args = Map<Object?, Object?>.from(connectCall!.arguments as Map);
    expect(args[VpnContract.argDnsAutomatic], isTrue);
    expect(args[VpnContract.argDnsServers], isEmpty);
    expect(find.textContaining('must be'), findsNothing);
    expect(statusText(tester), contains('Connecting'));
  });
}
