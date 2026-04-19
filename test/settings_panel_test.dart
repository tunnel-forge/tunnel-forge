import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tunnel_forge/profile_models.dart';
import 'package:tunnel_forge/widgets/settings_panel.dart';

void main() {
  Widget buildPanel({
    required ProxySettings proxySettings,
    ConnectionMode connectionMode = ConnectionMode.proxyOnly,
    ConnectivityCheckSettings connectivityCheckSettings =
        const ConnectivityCheckSettings(),
    ValueChanged<ProxySettings>? onProxySettingsChanged,
    ValueChanged<ConnectivityCheckSettings>? onConnectivityCheckSettingsChanged,
  }) {
    final theme = ThemeData.light();
    return MaterialApp(
      home: Scaffold(
        body: SettingsPanel(
          themeMode: ThemeMode.light,
          onThemeModeChanged: (_) {},
          connectionMode: connectionMode,
          routingMode: RoutingMode.fullTunnel,
          allowedAppPackages: const [],
          proxySettings: proxySettings,
          connectivityCheckSettings: connectivityCheckSettings,
          onConnectionModeChanged: (_) {},
          onRoutingModeChanged: (_) {},
          onProxySettingsChanged: onProxySettingsChanged ?? (_) {},
          onConnectivityCheckSettingsChanged:
              onConnectivityCheckSettingsChanged ?? (_) {},
          onChooseApps: () {},
          routingLocked: false,
          colorScheme: theme.colorScheme,
          textTheme: theme.textTheme,
        ),
      ),
    );
  }

  testWidgets('proxy port fields update when persisted settings load later', (
    tester,
  ) async {
    await tester.pumpWidget(buildPanel(proxySettings: const ProxySettings()));

    expect(find.text('8080'), findsOneWidget);
    expect(find.text('1080'), findsOneWidget);

    await tester.pumpWidget(
      buildPanel(
        proxySettings: const ProxySettings(httpPort: 18080, socksPort: 11080),
      ),
    );
    await tester.pump();

    expect(find.text('18080'), findsWidgets);
    expect(find.text('11080'), findsWidgets);
    expect(find.textContaining('127.0.0.1:18080'), findsWidgets);
    expect(find.textContaining('127.0.0.1:11080'), findsWidgets);
  });

  testWidgets('editing a proxy port emits parsed settings', (tester) async {
    ProxySettings? changed;

    await tester.pumpWidget(
      buildPanel(
        proxySettings: const ProxySettings(),
        onProxySettingsChanged: (value) {
          changed = value;
        },
      ),
    );

    await tester.enterText(
      find.byKey(const Key('proxy_http_port_field')),
      '18080',
    );
    await tester.pump();

    expect(changed?.httpPort, 18080);
    expect(changed?.socksPort, ProxySettings.defaultSocksPort);
  });

  testWidgets('connectivity URL field updates when persisted value changes', (
    tester,
  ) async {
    await tester.pumpWidget(
      buildPanel(
        proxySettings: const ProxySettings(),
        connectionMode: ConnectionMode.vpnTunnel,
      ),
    );

    expect(find.text(ConnectivityCheckSettings.defaultUrl), findsWidgets);

    await tester.pumpWidget(
      buildPanel(
        proxySettings: const ProxySettings(),
        connectionMode: ConnectionMode.vpnTunnel,
        connectivityCheckSettings: const ConnectivityCheckSettings(
          url: 'https://example.com/ping',
        ),
      ),
    );
    await tester.pump();

    expect(find.text('https://example.com/ping'), findsWidgets);
  });

  testWidgets('editing connectivity URL emits parsed global settings', (
    tester,
  ) async {
    ConnectivityCheckSettings? changed;

    await tester.pumpWidget(
      buildPanel(
        proxySettings: const ProxySettings(),
        connectionMode: ConnectionMode.vpnTunnel,
        onConnectivityCheckSettingsChanged: (value) {
          changed = value;
        },
      ),
    );

    await tester.enterText(
      find.byKey(const Key('connectivity_url_field')),
      'https://example.com/health',
    );
    await tester.pump();

    expect(changed?.url, 'https://example.com/health');
  });

  testWidgets('invalid connectivity URL shows validation error', (
    tester,
  ) async {
    await tester.pumpWidget(
      buildPanel(
        proxySettings: const ProxySettings(),
        connectionMode: ConnectionMode.vpnTunnel,
      ),
    );

    await tester.enterText(
      find.byKey(const Key('connectivity_url_field')),
      'ftp://example.com',
    );
    await tester.pump();

    expect(find.text('Only HTTP and HTTPS URLs are supported'), findsOneWidget);
  });
}
