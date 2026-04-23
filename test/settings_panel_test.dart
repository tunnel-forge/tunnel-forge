import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tunnel_forge/profile_models.dart';
import 'package:tunnel_forge/widgets/settings_panel.dart';

void main() {
  Finder connectivityUrlField() =>
      find.byKey(const Key('connectivity_url_field'));
  Finder connectivityTimeoutField() =>
      find.byKey(const Key('connectivity_timeout_field'));
  Finder settingsScrollView() => find.byType(Scrollable).first;
  Finder proxyEndpointsTitle() => find.text('Proxy endpoints');

  Widget buildPanel({
    required ProxySettings proxySettings,
    ConnectionMode connectionMode = ConnectionMode.proxyOnly,
    ProxyExposure? proxyExposure,
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
          proxyExposure: proxyExposure,
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

  testWidgets('proxy controls stay visible in VPN mode', (tester) async {
    await tester.pumpWidget(
      buildPanel(
        proxySettings: const ProxySettings(),
        connectionMode: ConnectionMode.vpnTunnel,
      ),
    );

    expect(find.byKey(const Key('proxy_allow_lan_switch')), findsOneWidget);
  });

  testWidgets('LAN switch emits updated proxy settings', (tester) async {
    ProxySettings? changed;

    await tester.pumpWidget(
      buildPanel(
        proxySettings: const ProxySettings(),
        onProxySettingsChanged: (value) {
          changed = value;
        },
      ),
    );

    await tester.tap(find.byKey(const Key('proxy_allow_lan_switch')));
    await tester.pump();

    expect(changed?.allowLanConnections, isTrue);
  });

  testWidgets('active proxy exposure shows detected LAN IP', (tester) async {
    await tester.pumpWidget(
      buildPanel(
        proxySettings: const ProxySettings(allowLanConnections: true),
        proxyExposure: const ProxyExposure(
          active: true,
          bindAddress: '192.168.1.24',
          displayAddress: '192.168.1.24',
          httpPort: 18080,
          socksPort: 11080,
          lanRequested: true,
          lanActive: true,
        ),
      ),
    );
    await tester.scrollUntilVisible(
      proxyEndpointsTitle(),
      200,
      scrollable: settingsScrollView(),
    );

    expect(find.textContaining('HTTP: 192.168.1.24:18080'), findsOneWidget);
    expect(find.textContaining('SOCKS5: 192.168.1.24:11080'), findsOneWidget);
    expect(
      find.textContaining('LAN clients on the same Wi-Fi or hotspot'),
      findsOneWidget,
    );
  });

  testWidgets('LAN warning is shown when no shareable address is available', (
    tester,
  ) async {
    await tester.pumpWidget(
      buildPanel(
        proxySettings: const ProxySettings(allowLanConnections: true),
        proxyExposure: const ProxyExposure(
          active: true,
          bindAddress: '127.0.0.1',
          displayAddress: '127.0.0.1',
          httpPort: 8080,
          socksPort: 1080,
          lanRequested: true,
          lanActive: false,
          warning:
              'LAN sharing is enabled, but no shareable local IPv4 is currently available.',
        ),
      ),
    );
    await tester.scrollUntilVisible(
      proxyEndpointsTitle(),
      200,
      scrollable: settingsScrollView(),
    );

    expect(
      find.textContaining('no shareable local IPv4 is currently available'),
      findsOneWidget,
    );
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

    await tester.scrollUntilVisible(
      connectivityUrlField(),
      200,
      scrollable: settingsScrollView(),
    );
    expect(find.text(ConnectivityCheckSettings.defaultUrl), findsWidgets);
    expect(
      find.text('${ConnectivityCheckSettings.defaultTimeoutMs}'),
      findsWidgets,
    );

    await tester.pumpWidget(
      buildPanel(
        proxySettings: const ProxySettings(),
        connectionMode: ConnectionMode.vpnTunnel,
        connectivityCheckSettings: const ConnectivityCheckSettings(
          url: 'https://example.com/ping',
          timeoutMs: 3200,
        ),
      ),
    );
    await tester.pump();

    expect(find.text('https://example.com/ping'), findsWidgets);
    expect(find.text('3200'), findsWidgets);
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

    await tester.scrollUntilVisible(
      connectivityUrlField(),
      200,
      scrollable: settingsScrollView(),
    );
    await tester.enterText(
      connectivityUrlField(),
      'https://example.com/health',
    );
    await tester.pump();

    expect(changed?.url, 'https://example.com/health');
    expect(changed?.timeoutMs, ConnectivityCheckSettings.defaultTimeoutMs);
  });

  testWidgets('editing connectivity timeout emits parsed global settings', (
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

    await tester.scrollUntilVisible(
      connectivityTimeoutField(),
      200,
      scrollable: settingsScrollView(),
    );
    await tester.enterText(connectivityTimeoutField(), '3200');
    await tester.pump();

    expect(changed?.url, ConnectivityCheckSettings.defaultUrl);
    expect(changed?.timeoutMs, 3200);
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

    await tester.scrollUntilVisible(
      connectivityUrlField(),
      200,
      scrollable: settingsScrollView(),
    );
    await tester.enterText(connectivityUrlField(), 'ftp://example.com');
    await tester.pump();

    expect(find.text('Only HTTP and HTTPS URLs are supported'), findsOneWidget);
  });

  testWidgets('invalid connectivity timeout shows validation error', (
    tester,
  ) async {
    await tester.pumpWidget(
      buildPanel(
        proxySettings: const ProxySettings(),
        connectionMode: ConnectionMode.vpnTunnel,
      ),
    );

    await tester.scrollUntilVisible(
      connectivityTimeoutField(),
      200,
      scrollable: settingsScrollView(),
    );
    await tester.enterText(connectivityTimeoutField(), '0');
    await tester.pump();

    expect(find.text('Enter a timeout greater than 0 ms'), findsOneWidget);
  });

  testWidgets('connectivity help text stays short and actionable', (
    tester,
  ) async {
    await tester.pumpWidget(
      buildPanel(
        proxySettings: const ProxySettings(),
        connectionMode: ConnectionMode.vpnTunnel,
      ),
    );

    await tester.scrollUntilVisible(
      connectivityUrlField(),
      200,
      scrollable: settingsScrollView(),
    );

    expect(
      find.textContaining('Used for the status check after you connect'),
      findsOneWidget,
    );
    expect(
      find.textContaining('Tap the badge anytime to refresh'),
      findsOneWidget,
    );
    expect(
      find.textContaining(
        'Maximum time to wait before marking the check unreachable',
      ),
      findsOneWidget,
    );
  });
}
