import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:tunnel_forge/main.dart';
import 'package:tunnel_forge/vpn_contract.dart';

import 'support/host_to_dart_channel.dart';
import 'support/vpn_channel_mock.dart';

// VPN channel is mocked here; JNI entrypoints are checked in androidTest (see JniEntryPointsTest).

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  String statusText(WidgetTester tester) {
    return tester.widget<Text>(find.byKey(const Key('vpn_status'))).data ?? '';
  }

  /// Allow deferred profile load and a few animation ticks. Avoid long
  /// [pumpAndSettle] caps here: nav and other widgets can leave micro-animations
  /// that never report fully idle.
  Future<void> pumpTunnelForgeApp(WidgetTester tester) async {
    addTearDown(() async {
      await tester.binding.setSurfaceSize(null);
    });
    await tester.binding.setSurfaceSize(const Size(480, 1200));
    await tester.pumpWidget(const TunnelForgeApp());
    await tester.pump();
    await tester.pump(const Duration(seconds: 2));
    await tester.pump(const Duration(milliseconds: 100));
  }

  testWidgets('smoke: cold start shows VPN tab', (WidgetTester tester) async {
    await pumpTunnelForgeApp(tester);

    expect(find.byKey(const Key('vpn_status')), findsOneWidget);
    expect(find.byKey(const Key('vpn_connect')), findsOneWidget);
  });

  testWidgets('connect flow with mocked native channel', (
    WidgetTester tester,
  ) async {
    SharedPreferences.setMockInitialValues({});
    final methods = <String>[];
    installVpnChannelMock(methods);
    addTearDown(uninstallVpnChannelMock);

    await pumpTunnelForgeApp(tester);

    await tester.tap(find.byKey(const Key('vpn_connect')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    expect(methods, [VpnContract.prepareVpn, VpnContract.connect]);
    expect(statusText(tester), contains('Connecting'));

    await tester.pump(const Duration(seconds: 60));
    await tester.pump();

    expect(statusText(tester), 'Connect');
  });

  testWidgets('bottom navigation: Logs then VPN', (WidgetTester tester) async {
    await pumpTunnelForgeApp(tester);

    await tester.tap(find.text('Logs'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.text('No activity yet'), findsOneWidget);

    await tester.tap(find.text('VPN'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.byKey(const Key('vpn_connect')), findsOneWidget);
  });

  testWidgets('prepareVpn denied surfaces message and does not connect', (
    WidgetTester tester,
  ) async {
    SharedPreferences.setMockInitialValues({});
    final methods = <String>[];
    installVpnChannelMock(methods, prepareResult: false);
    addTearDown(uninstallVpnChannelMock);

    await pumpTunnelForgeApp(tester);

    await tester.tap(find.byKey(const Key('vpn_connect')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    expect(methods, [VpnContract.prepareVpn]);
    expect(find.text('VPN permission is required'), findsOneWidget);
    expect(statusText(tester), 'Connect');
  });

  testWidgets('connect PlatformException is handled', (
    WidgetTester tester,
  ) async {
    SharedPreferences.setMockInitialValues({});
    final methods = <String>[];
    installVpnChannelMock(
      methods,
      onConnect: (_) async => throw PlatformException(
        code: 'TEST',
        message: 'Simulated native failure',
      ),
    );
    addTearDown(uninstallVpnChannelMock);

    await pumpTunnelForgeApp(tester);

    await tester.tap(find.byKey(const Key('vpn_connect')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    expect(methods, [VpnContract.prepareVpn, VpnContract.connect]);
    expect(find.text('Simulated native failure'), findsOneWidget);
    expect(statusText(tester), 'Connect');
  });

  testWidgets('host tunnel state connected updates UI', (
    WidgetTester tester,
  ) async {
    SharedPreferences.setMockInitialValues({});
    final methods = <String>[];
    installVpnChannelMock(methods);
    addTearDown(uninstallVpnChannelMock);

    await pumpTunnelForgeApp(tester);

    await tester.tap(find.byKey(const Key('vpn_connect')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    expect(statusText(tester), contains('Connecting'));

    await simulateHostTunnelState(tester, VpnTunnelState.connected, 'tun0');

    expect(statusText(tester), 'Connected');
    expect(methods, [VpnContract.prepareVpn, VpnContract.connect]);
  });
}
