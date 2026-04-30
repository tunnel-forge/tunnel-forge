import 'dart:convert';
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:tunnel_forge/core/network/connectivity_checker.dart';
import 'package:tunnel_forge/features/home/domain/home_models.dart';
import 'package:tunnel_forge/features/home/domain/home_repositories.dart';
import 'package:tunnel_forge/features/onboarding/domain/onboarding_repository.dart';
import 'package:tunnel_forge/main.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/features/profiles/data/profile_store.dart';
import 'package:tunnel_forge/app/theme/app_theme.dart';
import 'package:tunnel_forge/features/tunnel/data/vpn_contract.dart';
import 'package:tunnel_forge/features/home/presentation/widgets/connection_panel.dart';

import 'support/host_to_dart_channel.dart';
import 'support/vpn_channel_mock.dart';

class FakeConnectivityChecker implements ConnectivityChecker {
  final List<ConnectivityPingRequest> requests = <ConnectivityPingRequest>[];
  ConnectivityPingResult nextResult = ConnectivityPingResult.success(
    latencyMs: 84,
    statusCode: 204,
  );
  Completer<ConnectivityPingResult>? completer;

  List<String> get urls => requests.map((request) => request.url).toList();

  @override
  Future<ConnectivityPingResult> ping(ConnectivityPingRequest request) {
    requests.add(request);
    final pending = completer;
    if (pending != null) return pending.future;
    return Future<ConnectivityPingResult>.value(nextResult);
  }
}

class _AcceptedOnboardingRepository implements OnboardingRepository {
  const _AcceptedOnboardingRepository();

  @override
  Future<int?> loadAcknowledgedVersion() async => kCurrentL2tpDisclosureVersion;

  @override
  Future<void> saveAcknowledgedVersion(int version) async {}
}

class _FakeAppVersionRepository implements AppVersionRepository {
  const _FakeAppVersionRepository();

  @override
  Future<AppVersionInfo> loadInstalledVersion() async {
    return const AppVersionInfo(
      displayVersion: '0.3.0+11',
      semanticVersion: SemanticVersion(major: 0, minor: 3, patch: 0),
    );
  }
}

class _FakeAppUpdateRepository implements AppUpdateRepository {
  const _FakeAppUpdateRepository();

  @override
  Future<AppReleaseInfo> fetchLatestRelease() async {
    return AppReleaseInfo(
      version: const SemanticVersion(major: 0, minor: 3, patch: 0),
      htmlUrl: 'https://github.com/evokelektrique/tunnel-forge/releases',
      publishedAt: DateTime.utc(2026, 4, 19),
      prerelease: true,
    );
  }
}

Future<void> _openProfileActions(
  WidgetTester tester,
  String displayName,
) async {
  final profileTile = find.ancestor(
    of: find.text(displayName),
    matching: find.byType(ListTile),
  );
  expect(profileTile, findsOneWidget);

  final actionButton = find.descendant(
    of: profileTile,
    matching: find.byTooltip('Profile actions'),
  );
  expect(actionButton, findsOneWidget);

  await tester.tap(actionButton);
  await tester.pumpAndSettle();
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

  List<String> userInitiatedVpnMethods(List<String> methods) {
    return methods
        .where((method) => method != VpnContract.getRuntimeState)
        .toList(growable: false);
  }

  Future<void> pumpConnectionPanel(
    WidgetTester tester, {
    Brightness brightness = Brightness.light,
    bool busy = false,
    bool tunnelUp = false,
    bool awaitingTunnel = false,
    bool stopRequested = false,
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
              stopRequested: stopRequested,
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

  Future<void> settleConnectionAnimation(WidgetTester tester) async {
    await tester.pump(const Duration(milliseconds: 560));
  }

  Color? buttonBackground(WidgetTester tester, {bool disabled = false}) {
    final fill = tester.widget<AnimatedContainer>(
      find.byKey(const Key('vpn_connect_fill')),
    );
    final decoration = fill.decoration as BoxDecoration?;
    return decoration?.color;
  }

  Color? actionRingColor(WidgetTester tester) {
    final container = tester.widget<AnimatedContainer>(
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
      AppPalette.lightSurfaceContainerHighest,
    );
    expect(
      light.inputDecorationTheme.fillColor,
      AppPalette.lightSurfaceContainer,
    );
    expect(light.colorScheme.primary, AppPalette.lightPrimary);

    expect(dark.colorScheme.surface, AppPalette.darkSurface);
    expect(
      dark.colorScheme.surfaceContainerHighest,
      AppPalette.darkSurfaceContainerHighest,
    );
    expect(
      dark.inputDecorationTheme.fillColor,
      AppPalette.darkSurfaceContainer,
    );
    expect(dark.colorScheme.primary, AppPalette.darkPrimary);
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
                  stopRequested: false,
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
      expect(buttonBackground(tester), AppPalette.lightPrimary);
      expect(lightStatus.style?.color, AppPalette.lightOnSurfaceVariant);
      expect(find.text('Ready'), findsOneWidget);

      await pumpConnectionPanel(tester, brightness: Brightness.dark);
      await settleConnectionAnimation(tester);
      final darkStatus = tester.widget<Text>(
        find.byKey(const Key('vpn_status')),
      );
      expect(buttonBackground(tester), AppPalette.darkPrimary);
      expect(darkStatus.style?.color, AppPalette.darkOnSurfaceVariant);
      expect(find.text('Ready'), findsOneWidget);
    },
  );

  testWidgets(
    'ConnectionPanel uses amber progress and green connected colors',
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
        AppPalette.lightSurfaceContainerHighest,
      );
      expect(progress.color, const AppSemanticColors.light().connecting);
      expect(
        connectingStatus.style?.color,
        const AppSemanticColors.light().connecting,
      );
      expect(find.text('Connecting...'), findsOneWidget);

      await pumpConnectionPanel(tester, tunnelUp: true, label: 'Connected');
      await settleConnectionAnimation(tester);

      final connectedStatus = tester.widget<Text>(
        find.byKey(const Key('vpn_status')),
      );
      expect(
        buttonBackground(tester),
        const AppSemanticColors.light().connected,
      );
      expect(
        actionRingColor(tester),
        const AppSemanticColors.light().connected.withValues(alpha: 0.32),
      );
      expect(
        connectedStatus.style?.color,
        const AppSemanticColors.light().connected,
      );
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
      AppPalette.lightSurfaceContainerHighest,
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
        onboardingRepository: const _AcceptedOnboardingRepository(),
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

    expect(userInitiatedVpnMethods(methods), [
      VpnContract.setLogLevel,
      VpnContract.prepareVpn,
      VpnContract.connect,
    ]);
    expect(statusText(tester), contains('Connecting'));

    // Mock never delivers TUN; the await timeout logs a reminder and keeps waiting.
    await tester.pump(const Duration(seconds: 60));
    await tester.pump();

    expect(statusText(tester), contains('Connecting'));
  });

  testWidgets('connecting state lets the user cancel before TUN comes up', (
    WidgetTester tester,
  ) async {
    SharedPreferences.setMockInitialValues({
      ProfileStore.prefsKeyProfilesJson: jsonEncode([
        {
          'id': 'widget_test_cancel_connect',
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
      ProfileStore.prefsKeyLastProfileId: 'widget_test_cancel_connect',
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
        onboardingRepository: const _AcceptedOnboardingRepository(),
        profileStore: ProfileStore(secretsOverride: MemorySecretStore()),
      ),
    );

    await tester.pump();
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }

    await tester.tap(find.byKey(const Key('vpn_connect')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    expect(statusText(tester), contains('Connecting'));

    await tester.tap(find.byKey(const Key('vpn_connect')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    expect(userInitiatedVpnMethods(methods), [
      VpnContract.setLogLevel,
      VpnContract.prepareVpn,
      VpnContract.connect,
      VpnContract.disconnect,
    ]);
    expect(statusText(tester), 'Canceling...');

    await simulateHostTunnelState(tester, VpnTunnelState.stopped, 'Canceled');
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
        onboardingRepository: const _AcceptedOnboardingRepository(),
        profileStore: ProfileStore(secretsOverride: MemorySecretStore()),
        connectivityChecker: checker,
        appVersionRepository: const _FakeAppVersionRepository(),
        appUpdateRepository: const _FakeAppUpdateRepository(),
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
    expect(checker.requests, [
      const ConnectivityPingRequest.directWithTimeout(
        url: ConnectivityCheckSettings.defaultUrl,
        timeoutMs: ConnectivityCheckSettings.defaultTimeoutMs,
      ),
      const ConnectivityPingRequest.directWithTimeout(
        url: ConnectivityCheckSettings.defaultUrl,
        timeoutMs: ConnectivityCheckSettings.defaultTimeoutMs,
      ),
    ]);

    checker.completer!.complete(
      ConnectivityPingResult.success(latencyMs: 91, statusCode: 204),
    );
    await tester.pump();
    await tester.pump();

    expect(connectivityStatusText(tester), '91 ms');
  });

  testWidgets('connect does not auto-run connectivity check before connected', (
    WidgetTester tester,
  ) async {
    SharedPreferences.setMockInitialValues({
      ProfileStore.prefsKeyProfilesJson: jsonEncode([
        {
          'id': 'widget_test_connecting_only',
          'displayName': 'Connecting Only',
          'server': 'vpn.test.example',
          'user': '',
          'dnsAutomatic': true,
          'dns1Host': '',
          'dns1Protocol': 'dnsOverUdp',
          'dns2Host': '',
          'dns2Protocol': 'dnsOverUdp',
        },
      ]),
      ProfileStore.prefsKeyLastProfileId: 'widget_test_connecting_only',
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
        onboardingRepository: const _AcceptedOnboardingRepository(),
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

    expect(statusText(tester), contains('Connecting'));
    expect(checker.urls, isEmpty);
    expect(find.byKey(const Key('connectivity_status')), findsNothing);
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
        onboardingRepository: const _AcceptedOnboardingRepository(),
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

    expect(checker.requests, [
      const ConnectivityPingRequest.directWithTimeout(
        url: ConnectivityCheckSettings.defaultUrl,
        timeoutMs: ConnectivityCheckSettings.defaultTimeoutMs,
      ),
    ]);
    expect(connectivityStatusText(tester), '84 ms');
  });

  testWidgets('saved connectivity timeout is applied to ping requests', (
    WidgetTester tester,
  ) async {
    SharedPreferences.setMockInitialValues({
      ProfileStore.prefsKeyProfilesJson: jsonEncode([
        {
          'id': 'widget_test_connectivity_timeout',
          'displayName': 'Connectivity Timeout',
          'server': 'vpn.test.example',
          'user': '',
          'dnsAutomatic': true,
          'dns1Host': '',
          'dns1Protocol': 'dnsOverUdp',
          'dns2Host': '',
          'dns2Protocol': 'dnsOverUdp',
        },
      ]),
      ProfileStore.prefsKeyLastProfileId: 'widget_test_connectivity_timeout',
      ProfileStore.prefsKeyConnectivityCheckTimeoutMs: 3200,
    });
    final checker = FakeConnectivityChecker();
    installVpnChannelMock(<String>[]);
    addTearDown(uninstallVpnChannelMock);

    await tester.pumpWidget(
      TunnelForgeApp(
        onboardingRepository: const _AcceptedOnboardingRepository(),
        profileStore: ProfileStore(secretsOverride: MemorySecretStore()),
        connectivityChecker: checker,
      ),
    );

    await tester.pump();
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }

    await simulateHostTunnelState(tester, VpnTunnelState.connected, 'tun0');
    await tester.pump();

    expect(checker.requests, [
      const ConnectivityPingRequest.directWithTimeout(
        url: ConnectivityCheckSettings.defaultUrl,
        timeoutMs: 3200,
      ),
    ]);
  });

  testWidgets('per-app VPN auto-runs connectivity check directly', (
    WidgetTester tester,
  ) async {
    SharedPreferences.setMockInitialValues({
      ProfileStore.prefsKeyProfilesJson: jsonEncode([
        {
          'id': 'widget_test_per_app_connectivity',
          'displayName': 'Per-App Connectivity',
          'server': 'vpn.test.example',
          'user': '',
          'dnsAutomatic': true,
          'dns1Host': '',
          'dns1Protocol': 'dnsOverUdp',
          'dns2Host': '',
          'dns2Protocol': 'dnsOverUdp',
        },
      ]),
      ProfileStore.prefsKeyLastProfileId: 'widget_test_per_app_connectivity',
      ProfileStore.prefsKeySplitTunnelEnabled: true,
      ProfileStore.prefsKeySplitTunnelMode: SplitTunnelMode.inclusive.jsonValue,
      ProfileStore.prefsKeySplitTunnelInclusivePackages: const <String>[
        'com.example.allowed',
      ],
    });
    final checker = FakeConnectivityChecker();
    installVpnChannelMock(<String>[]);
    addTearDown(uninstallVpnChannelMock);

    await tester.pumpWidget(
      TunnelForgeApp(
        onboardingRepository: const _AcceptedOnboardingRepository(),
        profileStore: ProfileStore(secretsOverride: MemorySecretStore()),
        connectivityChecker: checker,
      ),
    );

    await tester.pump();
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }

    await simulateHostTunnelState(tester, VpnTunnelState.connected, 'tun0');
    await tester.pump();

    expect(checker.requests, [
      const ConnectivityPingRequest.directWithTimeout(
        url: ConnectivityCheckSettings.defaultUrl,
        timeoutMs: ConnectivityCheckSettings.defaultTimeoutMs,
      ),
    ]);
  });

  testWidgets('proxy-only auto-runs connectivity check through HTTP proxy', (
    WidgetTester tester,
  ) async {
    SharedPreferences.setMockInitialValues({
      ProfileStore.prefsKeyProfilesJson: jsonEncode([
        {
          'id': 'widget_test_proxy_connectivity',
          'displayName': 'Proxy Connectivity',
          'server': 'vpn.test.example',
          'user': '',
          'dnsAutomatic': true,
          'dns1Host': '',
          'dns1Protocol': 'dnsOverUdp',
          'dns2Host': '',
          'dns2Protocol': 'dnsOverUdp',
        },
      ]),
      ProfileStore.prefsKeyLastProfileId: 'widget_test_proxy_connectivity',
      ProfileStore.prefsKeyConnectionMode: 'proxyOnly',
      ProfileStore.prefsKeyProxyHttpPort: 18080,
    });
    final checker = FakeConnectivityChecker();
    installVpnChannelMock(<String>[]);
    addTearDown(uninstallVpnChannelMock);

    await tester.pumpWidget(
      TunnelForgeApp(
        onboardingRepository: const _AcceptedOnboardingRepository(),
        profileStore: ProfileStore(secretsOverride: MemorySecretStore()),
        connectivityChecker: checker,
      ),
    );

    await tester.pump();
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }

    await simulateHostTunnelState(tester, VpnTunnelState.connected, 'proxy0');
    await tester.pump();

    expect(checker.requests, [
      const ConnectivityPingRequest.localHttpProxy(
        url: ConnectivityCheckSettings.defaultUrl,
        timeoutMs: ConnectivityCheckSettings.defaultTimeoutMs,
        proxyPort: 18080,
      ),
    ]);
    expect(connectivityStatusText(tester), '84 ms');
  });

  testWidgets('closing editor for another profile returns to the picker list', (
    WidgetTester tester,
  ) async {
    SharedPreferences.setMockInitialValues({
      ProfileStore.prefsKeyProfilesJson: jsonEncode([
        {
          'id': 'active_profile',
          'displayName': 'Primary',
          'server': 'primary.example.com',
          'user': '',
          'dnsAutomatic': true,
          'dns1Host': '',
          'dns1Protocol': 'dnsOverUdp',
          'dns2Host': '',
          'dns2Protocol': 'dnsOverUdp',
        },
        {
          'id': 'other_profile',
          'displayName': 'Secondary',
          'server': 'secondary.example.com',
          'user': '',
          'dnsAutomatic': true,
          'dns1Host': '',
          'dns1Protocol': 'dnsOverUdp',
          'dns2Host': '',
          'dns2Protocol': 'dnsOverUdp',
        },
      ]),
      ProfileStore.prefsKeyLastProfileId: 'active_profile',
    });
    final methods = <String>[];
    installVpnChannelMock(methods);
    addTearDown(uninstallVpnChannelMock);

    await tester.pumpWidget(
      TunnelForgeApp(
        onboardingRepository: const _AcceptedOnboardingRepository(),
        profileStore: ProfileStore(secretsOverride: MemorySecretStore()),
      ),
    );
    await tester.pump();
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }

    expect(find.text('Primary'), findsOneWidget);
    expect(find.text('primary.example.com'), findsOneWidget);

    await tester.tap(find.byKey(const Key('profile_picker_tile')));
    await tester.pumpAndSettle();
    await _openProfileActions(tester, 'Secondary');
    await tester.tap(find.text('Edit profile'));
    await tester.pumpAndSettle();
    await tester.tap(find.byIcon(Icons.close).first);
    await tester.pumpAndSettle();

    expect(find.text('Profiles'), findsOneWidget);
    expect(find.text('Primary'), findsNWidgets(2));
    expect(find.text('primary.example.com'), findsNWidgets(2));
    expect(find.text('Secondary'), findsOneWidget);
  });

  testWidgets('new profile draft does not persist until save', (
    WidgetTester tester,
  ) async {
    SharedPreferences.setMockInitialValues({});
    installVpnChannelMock(<String>[]);
    addTearDown(uninstallVpnChannelMock);
    addTearDown(() async {
      await tester.binding.setSurfaceSize(null);
    });
    await tester.binding.setSurfaceSize(const Size(480, 1200));

    final store = ProfileStore(secretsOverride: MemorySecretStore());
    await tester.pumpWidget(
      TunnelForgeApp(
        onboardingRepository: const _AcceptedOnboardingRepository(),
        profileStore: store,
      ),
    );
    await tester.pump();
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }

    await tester.tap(find.byKey(const Key('profile_picker_tile')));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Add profile'));
    await tester.pumpAndSettle();
    expect(find.text('Create new profile'), findsOneWidget);
    expect(find.text('Import from file'), findsOneWidget);
    expect(find.text('Import from clipboard'), findsOneWidget);

    await tester.tap(find.text('Create new profile').last);
    await tester.pumpAndSettle();

    expect(find.text('Profile details'), findsOneWidget);
    expect(await store.loadProfiles(), isEmpty);

    await tester.tap(find.byIcon(Icons.close).first);
    await tester.pumpAndSettle();

    expect(find.text('Profiles'), findsOneWidget);
    expect(
      find.text(
        'No profiles yet. Tap + to create or import your first profile.',
      ),
      findsOneWidget,
    );
    expect(await store.loadProfiles(), isEmpty);
  });

  testWidgets('saving a new profile stays in the picker flow and selects it', (
    WidgetTester tester,
  ) async {
    SharedPreferences.setMockInitialValues({});
    installVpnChannelMock(<String>[]);
    addTearDown(uninstallVpnChannelMock);
    addTearDown(() async {
      await tester.binding.setSurfaceSize(null);
    });
    await tester.binding.setSurfaceSize(const Size(480, 1200));

    final store = ProfileStore(secretsOverride: MemorySecretStore());
    await tester.pumpWidget(
      TunnelForgeApp(
        onboardingRepository: const _AcceptedOnboardingRepository(),
        profileStore: store,
      ),
    );
    await tester.pump();
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }

    await tester.tap(find.byKey(const Key('profile_picker_tile')));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Add profile'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Create new profile').last);
    await tester.pumpAndSettle();

    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    final profiles = await store.loadProfiles();
    expect(profiles, hasLength(1));
    expect(profiles.single.displayName, 'New profile');
    expect(profiles.single.server, 'vpn.example.com');
    expect(find.text('Profiles'), findsOneWidget);
    expect(find.text('Profile saved'), findsOneWidget);
    expect(find.text('New profile'), findsWidgets);
    expect(find.text('vpn.example.com'), findsWidgets);
  });

  testWidgets('logs default to debug level and use cumulative filtering', (
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
        onboardingRepository: const _AcceptedOnboardingRepository(),
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

    expect(find.textContaining('info message'), findsOneWidget);
    expect(find.textContaining('warn message'), findsOneWidget);
    expect(find.textContaining('error message'), findsOneWidget);
    expect(find.textContaining('debug message'), findsOneWidget);
    expect(find.text('DEBUG'), findsOneWidget);

    await tester.tap(find.byTooltip('Log level'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('WARNING').last);
    await tester.pumpAndSettle();

    expect(find.textContaining('info message'), findsNothing);
    expect(find.textContaining('warn message'), findsOneWidget);
    expect(find.textContaining('error message'), findsOneWidget);
    expect(find.textContaining('debug message'), findsNothing);
    expect(find.text('Log level: WARNING'), findsNothing);
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
        onboardingRepository: const _AcceptedOnboardingRepository(),
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
          'connect server=vpn.example.com password=hunter2 target=secure.example.net:443 uri=https://example.com/token dns=a.example[UDP],b.example[TLS] expected=12345 clientIpv4=192.168.1.20 source=10.0.0.2:5353 public=8.8.8.8',
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
    expect(find.textContaining('8.8.8.8'), findsNothing);
    expect(find.textContaining('server=[REDACTED_HOST]'), findsOneWidget);
    expect(find.textContaining('password=[REDACTED]'), findsOneWidget);
    expect(find.textContaining('target=[REDACTED_TARGET]:443'), findsOneWidget);
    expect(find.textContaining('uri=[REDACTED_URI]'), findsOneWidget);
    expect(find.textContaining('dns=[REDACTED_HOST]'), findsOneWidget);
    expect(find.textContaining('expected=12345'), findsOneWidget);
    expect(find.textContaining('clientIpv4=192.168.1.20'), findsOneWidget);
    expect(find.textContaining('source=10.0.0.2:5353'), findsOneWidget);
    expect(find.textContaining('public=[REDACTED_HOST]'), findsOneWidget);
  });

  testWidgets('debug log level enables immediately without confirmation', (
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
        onboardingRepository: const _AcceptedOnboardingRepository(),
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
    await tester.tap(find.text('INFO').last);
    await tester.pumpAndSettle();

    await tester.tap(find.byTooltip('Log level'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('DEBUG').last);
    await tester.pumpAndSettle();

    expect(find.text('Enable debug logs?'), findsNothing);
    expect(
      find.textContaining('may slow down the tunnel and the device'),
      findsNothing,
    );
    expect(find.text('DEBUG'), findsOneWidget);
    expect(methods.where((method) => method == VpnContract.setLogLevel), [
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
        onboardingRepository: const _AcceptedOnboardingRepository(),
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
        onboardingRepository: const _AcceptedOnboardingRepository(),
        profileStore: ProfileStore(secretsOverride: MemorySecretStore()),
      ),
    );

    await tester.pump();
    for (var i = 0; i < 50; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }

    expect(find.byKey(const Key('profile_picker_tile')), findsOneWidget);
  });

  testWidgets('deleting a profile refreshes the picker list immediately', (
    WidgetTester tester,
  ) async {
    SharedPreferences.setMockInitialValues({
      ProfileStore.prefsKeyProfilesJson: jsonEncode([
        {
          'id': 'widget_test_delete_primary',
          'displayName': 'Primary',
          'server': 'primary.example.com',
          'user': '',
          'dnsAutomatic': true,
          'dns1Host': '',
          'dns1Protocol': 'dnsOverUdp',
          'dns2Host': '',
          'dns2Protocol': 'dnsOverUdp',
        },
        {
          'id': 'widget_test_delete_secondary',
          'displayName': 'Secondary',
          'server': 'secondary.example.com',
          'user': '',
          'dnsAutomatic': true,
          'dns1Host': '',
          'dns1Protocol': 'dnsOverUdp',
          'dns2Host': '',
          'dns2Protocol': 'dnsOverUdp',
        },
      ]),
      ProfileStore.prefsKeyLastProfileId: 'widget_test_delete_primary',
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
        onboardingRepository: const _AcceptedOnboardingRepository(),
        profileStore: ProfileStore(secretsOverride: MemorySecretStore()),
      ),
    );

    await tester.pump();
    for (var i = 0; i < 50; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }

    await tester.tap(find.byKey(const Key('profile_picker_tile')));
    await tester.pumpAndSettle();
    expect(find.text('Primary'), findsWidgets);
    expect(find.text('Secondary'), findsOneWidget);

    await _openProfileActions(tester, 'Secondary');
    await tester.tap(find.text('Delete profile'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Delete'));
    await tester.pumpAndSettle();

    expect(find.text('Primary'), findsWidgets);
    expect(find.text('Secondary'), findsNothing);
    expect(find.text('secondary.example.com'), findsNothing);
  });

  testWidgets('canceling profile delete keeps the picker list unchanged', (
    WidgetTester tester,
  ) async {
    SharedPreferences.setMockInitialValues({
      ProfileStore.prefsKeyProfilesJson: jsonEncode([
        {
          'id': 'widget_test_delete_cancel_primary',
          'displayName': 'Primary',
          'server': 'primary.example.com',
          'user': '',
          'dnsAutomatic': true,
          'dns1Host': '',
          'dns1Protocol': 'dnsOverUdp',
          'dns2Host': '',
          'dns2Protocol': 'dnsOverUdp',
        },
        {
          'id': 'widget_test_delete_cancel_secondary',
          'displayName': 'Secondary',
          'server': 'secondary.example.com',
          'user': '',
          'dnsAutomatic': true,
          'dns1Host': '',
          'dns1Protocol': 'dnsOverUdp',
          'dns2Host': '',
          'dns2Protocol': 'dnsOverUdp',
        },
      ]),
      ProfileStore.prefsKeyLastProfileId: 'widget_test_delete_cancel_primary',
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
        onboardingRepository: const _AcceptedOnboardingRepository(),
        profileStore: ProfileStore(secretsOverride: MemorySecretStore()),
      ),
    );

    await tester.pump();
    for (var i = 0; i < 50; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }

    await tester.tap(find.byKey(const Key('profile_picker_tile')));
    await tester.pumpAndSettle();

    await _openProfileActions(tester, 'Secondary');
    await tester.tap(find.text('Delete profile'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();

    expect(find.text('Primary'), findsWidgets);
    expect(find.text('Secondary'), findsOneWidget);
    expect(find.text('secondary.example.com'), findsOneWidget);
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
        onboardingRepository: const _AcceptedOnboardingRepository(),
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
        onboardingRepository: const _AcceptedOnboardingRepository(),
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

    expect(userInitiatedVpnMethods(methods), [
      VpnContract.setLogLevel,
      VpnContract.connect,
    ]);
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
        onboardingRepository: const _AcceptedOnboardingRepository(),
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

    expect(userInitiatedVpnMethods(methods), [
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

  testWidgets('dns-over-https accepts endpoint path input and preserves it', (
    WidgetTester tester,
  ) async {
    SharedPreferences.setMockInitialValues({
      ProfileStore.prefsKeyProfilesJson: jsonEncode([
        {
          'id': 'widget_test_doh_path',
          'displayName': 'DoH path',
          'server': 'vpn.test.example',
          'user': '',
          'dnsAutomatic': false,
          'dns1Host': 'wikimedia-dns.org/dns-query',
          'dns1Protocol': 'dnsOverHttps',
          'dns2Host': '',
          'dns2Protocol': 'dnsOverUdp',
        },
      ]),
      ProfileStore.prefsKeyLastProfileId: 'widget_test_doh_path',
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
        onboardingRepository: const _AcceptedOnboardingRepository(),
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

    expect(userInitiatedVpnMethods(methods), [
      VpnContract.setLogLevel,
      VpnContract.prepareVpn,
      VpnContract.connect,
    ]);
    final args = Map<Object?, Object?>.from(connectCall!.arguments as Map);
    expect(args[VpnContract.argDnsAutomatic], isFalse);
    expect(args[VpnContract.argDnsServers], [
      {
        VpnContract.argDnsServerHost: 'wikimedia-dns.org/dns-query',
        VpnContract.argDnsServerProtocol: DnsProtocol.dnsOverHttps.jsonValue,
      },
    ]);
    expect(find.textContaining('must be'), findsNothing);
    expect(statusText(tester), contains('Connecting'));
  });
}
