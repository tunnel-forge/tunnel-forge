import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:tunnel_forge/main.dart';
import 'package:tunnel_forge/profile_store.dart';
import 'package:tunnel_forge/theme.dart';
import 'package:tunnel_forge/vpn_contract.dart';
import 'package:tunnel_forge/widgets/connection_panel.dart';

import '../integration_test/support/vpn_channel_mock.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  String statusText(WidgetTester tester) {
    return tester.widget<Text>(find.byKey(const Key('vpn_status'))).data ?? '';
  }

  Future<void> pumpConnectionPanel(
    WidgetTester tester, {
    Brightness brightness = Brightness.light,
    bool busy = false,
    bool tunnelUp = false,
    bool awaitingTunnel = false,
    String label = 'Connect',
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
              connectButtonLabel: label,
              onPrimary: () {},
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
                  connectButtonLabel: 'Connect',
                  onPrimary: () {},
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
      expect(find.text('Connect'), findsOneWidget);

      await pumpConnectionPanel(tester, brightness: Brightness.dark);
      final darkStatus = tester.widget<Text>(
        find.byKey(const Key('vpn_status')),
      );
      expect(buttonBackground(tester), AppPalette.darkConnectIdle);
      expect(darkStatus.style?.color, AppPalette.darkOnSurfaceVariant);
      expect(find.text('Connect'), findsOneWidget);
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
          'dns': '8.8.8.8',
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

    expect(methods, [VpnContract.prepareVpn, VpnContract.connect]);
    expect(statusText(tester), contains('Connecting'));

    // Mock never delivers TUN; UI clears "Connecting..." after the 60s await timeout.
    await tester.pump(const Duration(seconds: 60));
    await tester.pump();

    expect(statusText(tester), 'Connect');
  });

  testWidgets('VPN tab shows profile picker tile', (WidgetTester tester) async {
    SharedPreferences.setMockInitialValues({
      ProfileStore.prefsKeyProfilesJson: jsonEncode([
        {
          'id': 'widget_test_p2',
          'displayName': 'Test',
          'server': 'vpn.test.example',
          'user': '',
          'dns': '8.8.8.8',
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
          'dns': '8.8.8.8',
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

    expect(methods, [VpnContract.connect]);
    expect(statusText(tester), contains('Connecting'));
  });
}
