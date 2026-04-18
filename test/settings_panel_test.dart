import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tunnel_forge/profile_models.dart';
import 'package:tunnel_forge/widgets/settings_panel.dart';

void main() {
  Widget buildPanel({
    required ProxySettings proxySettings,
    ValueChanged<ProxySettings>? onProxySettingsChanged,
  }) {
    final theme = ThemeData.light();
    return MaterialApp(
      home: Scaffold(
        body: SettingsPanel(
          themeMode: ThemeMode.light,
          onThemeModeChanged: (_) {},
          connectionMode: ConnectionMode.proxyOnly,
          routingMode: RoutingMode.fullTunnel,
          allowedAppPackages: const [],
          proxySettings: proxySettings,
          onConnectionModeChanged: (_) {},
          onRoutingModeChanged: (_) {},
          onProxySettingsChanged: onProxySettingsChanged ?? (_) {},
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
    expect(find.textContaining('HTTP: 127.0.0.1:18080'), findsOneWidget);
    expect(find.textContaining('SOCKS5: 127.0.0.1:11080'), findsOneWidget);
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
}
