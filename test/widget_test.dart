import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:tunnel_forge/main.dart';
import 'package:tunnel_forge/profile_store.dart';
import 'package:tunnel_forge/vpn_contract.dart';

import '../integration_test/support/vpn_channel_mock.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  String statusText(WidgetTester tester) {
    return tester.widget<Text>(find.byKey(const Key('vpn_status'))).data ?? '';
  }

  testWidgets('Connect runs prepare then connect; awaits TUN status', (WidgetTester tester) async {
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
}
