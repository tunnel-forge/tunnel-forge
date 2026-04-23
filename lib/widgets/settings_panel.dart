import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../profile_models.dart';
import '../theme.dart';

/// Theme mode, connection mode, VPN routing, and global local-proxy settings.
class SettingsPanel extends StatefulWidget {
  const SettingsPanel({
    super.key,
    this.themeMode,
    this.onThemeModeChanged,
    required this.connectionMode,
    required this.routingMode,
    required this.allowedAppPackages,
    required this.proxySettings,
    this.proxyExposure,
    required this.connectivityCheckSettings,
    required this.onConnectionModeChanged,
    required this.onRoutingModeChanged,
    required this.onProxySettingsChanged,
    required this.onConnectivityCheckSettingsChanged,
    required this.onChooseApps,
    required this.routingLocked,
    required this.colorScheme,
    required this.textTheme,
  });

  final ThemeMode? themeMode;
  final ValueChanged<ThemeMode>? onThemeModeChanged;
  final ConnectionMode connectionMode;
  final RoutingMode routingMode;
  final List<String> allowedAppPackages;
  final ProxySettings proxySettings;
  final ProxyExposure? proxyExposure;
  final ConnectivityCheckSettings connectivityCheckSettings;
  final ValueChanged<ConnectionMode> onConnectionModeChanged;
  final ValueChanged<RoutingMode> onRoutingModeChanged;
  final ValueChanged<ProxySettings> onProxySettingsChanged;
  final ValueChanged<ConnectivityCheckSettings>
  onConnectivityCheckSettingsChanged;
  final VoidCallback onChooseApps;
  final bool routingLocked;
  final ColorScheme colorScheme;
  final TextTheme textTheme;

  @override
  State<SettingsPanel> createState() => _SettingsPanelState();
}

class _SettingsPanelState extends State<SettingsPanel> {
  static const EdgeInsets _kCardTilePadding = EdgeInsets.fromLTRB(
    16,
    12,
    16,
    12,
  );
  static const EdgeInsets _kCardFieldPadding = EdgeInsets.fromLTRB(
    16,
    12,
    16,
    20,
  );
  static const EdgeInsets _kCardContentPadding = EdgeInsets.fromLTRB(
    16,
    16,
    16,
    20,
  );

  late final TextEditingController _httpPortController;
  late final TextEditingController _socksPortController;
  late final TextEditingController _connectivityUrlController;
  late final TextEditingController _connectivityTimeoutController;
  String? _connectivityUrlError;
  String? _connectivityTimeoutError;

  @override
  void initState() {
    super.initState();
    _httpPortController = TextEditingController(
      text: '${widget.proxySettings.httpPort}',
    );
    _socksPortController = TextEditingController(
      text: '${widget.proxySettings.socksPort}',
    );
    _connectivityUrlController = TextEditingController(
      text: widget.connectivityCheckSettings.url,
    );
    _connectivityTimeoutController = TextEditingController(
      text: '${widget.connectivityCheckSettings.timeoutMs}',
    );
  }

  @override
  void didUpdateWidget(covariant SettingsPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.proxySettings.httpPort != widget.proxySettings.httpPort) {
      final nextText = '${widget.proxySettings.httpPort}';
      if (_httpPortController.text != nextText) {
        _httpPortController.text = nextText;
      }
    }
    if (oldWidget.proxySettings.socksPort != widget.proxySettings.socksPort) {
      final nextText = '${widget.proxySettings.socksPort}';
      if (_socksPortController.text != nextText) {
        _socksPortController.text = nextText;
      }
    }
    if (oldWidget.connectivityCheckSettings.url !=
        widget.connectivityCheckSettings.url) {
      final nextText = widget.connectivityCheckSettings.url;
      if (_connectivityUrlController.text != nextText) {
        _connectivityUrlController.text = nextText;
      }
      if (_connectivityUrlError != null) {
        _connectivityUrlError = null;
      }
    }
    if (oldWidget.connectivityCheckSettings.timeoutMs !=
        widget.connectivityCheckSettings.timeoutMs) {
      final nextText = '${widget.connectivityCheckSettings.timeoutMs}';
      if (_connectivityTimeoutController.text != nextText) {
        _connectivityTimeoutController.text = nextText;
      }
      if (_connectivityTimeoutError != null) {
        _connectivityTimeoutError = null;
      }
    }
  }

  @override
  void dispose() {
    _httpPortController.dispose();
    _socksPortController.dispose();
    _connectivityUrlController.dispose();
    _connectivityTimeoutController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final allAppsVpn = widget.routingMode == RoutingMode.fullTunnel;
    final perAppSubtitle = widget.allowedAppPackages.isEmpty
        ? 'No apps selected. Choose at least one app to connect.'
        : '${widget.allowedAppPackages.length} app${widget.allowedAppPackages.length == 1 ? '' : 's'} will use VPN';
    final proxyMode = widget.connectionMode == ConnectionMode.proxyOnly;
    final activeProxyExposure = widget.proxyExposure?.active == true
        ? widget.proxyExposure
        : null;
    final proxyDisplayHost = activeProxyExposure?.displayAddress ?? '127.0.0.1';
    final proxyHttpPort =
        activeProxyExposure?.httpPort ?? widget.proxySettings.httpPort;
    final proxySocksPort =
        activeProxyExposure?.socksPort ?? widget.proxySettings.socksPort;
    final proxyEndpointStatus = switch (activeProxyExposure) {
      ProxyExposure exposure when exposure.hasWarning => exposure.warning!,
      ProxyExposure exposure when exposure.lanActive =>
        'LAN clients on the same Wi-Fi or hotspot can use this address.',
      ProxyExposure exposure when exposure.lanRequested =>
        'LAN sharing is enabled for this session.',
      _ when widget.proxySettings.allowLanConnections =>
        'Connect to detect the current LAN IP for other devices.',
      _ => 'LAN access is disabled.',
    };
    final semanticColors =
        Theme.of(context).extension<AppSemanticColors>() ??
        AppSemanticColors.fallback(widget.colorScheme.brightness);

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
      children: [
        if (widget.themeMode != null && widget.onThemeModeChanged != null) ...[
          _sectionTitle('Appearance'),
          const SizedBox(height: 8),
          SegmentedButton<ThemeMode>(
            segments: const [
              ButtonSegment<ThemeMode>(
                value: ThemeMode.system,
                label: Text('System'),
                icon: Icon(Icons.brightness_auto_outlined, size: 18),
              ),
              ButtonSegment<ThemeMode>(
                value: ThemeMode.light,
                label: Text('Light'),
                icon: Icon(Icons.light_mode_outlined, size: 18),
              ),
              ButtonSegment<ThemeMode>(
                value: ThemeMode.dark,
                label: Text('Dark'),
                icon: Icon(Icons.dark_mode_outlined, size: 18),
              ),
            ],
            selected: {widget.themeMode!},
            onSelectionChanged: (next) {
              if (next.isEmpty) return;
              widget.onThemeModeChanged!(next.first);
            },
          ),
          const SizedBox(height: 22),
        ],
        _sectionTitle('Connection mode'),
        const SizedBox(height: 8),
        SegmentedButton<ConnectionMode>(
          segments: const [
            ButtonSegment<ConnectionMode>(
              value: ConnectionMode.vpnTunnel,
              label: Text('VPN'),
              icon: Icon(Icons.vpn_key_outlined, size: 18),
            ),
            ButtonSegment<ConnectionMode>(
              value: ConnectionMode.proxyOnly,
              label: Text('Local proxy'),
              icon: Icon(Icons.lan_outlined, size: 18),
            ),
          ],
          selected: {widget.connectionMode},
          onSelectionChanged: widget.routingLocked
              ? null
              : (next) {
                  if (next.isEmpty) return;
                  widget.onConnectionModeChanged(next.first);
                },
        ),
        const SizedBox(height: 22),
        if (!proxyMode) ...[
          _sectionTitle('Split tunneling'),
          const SizedBox(height: 8),
          Card(
            margin: EdgeInsets.zero,
            clipBehavior: Clip.antiAlias,
            child: Padding(
              padding: _kCardContentPadding,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _cardTitle('Mode'),
                  _cardText(
                    allAppsVpn
                        ? 'Exclusive routes all device traffic through the VPN.'
                        : 'Inclusive routes only the apps you choose through the VPN.',
                    color: widget.colorScheme.onSurfaceVariant,
                  ),
                  const SizedBox(height: 14),
                  SizedBox(
                    width: double.infinity,
                    child: SegmentedButton<RoutingMode>(
                      showSelectedIcon: false,
                      segments: const [
                        ButtonSegment<RoutingMode>(
                          value: RoutingMode.perAppAllowList,
                          label: Text('Inclusive'),
                        ),
                        ButtonSegment<RoutingMode>(
                          value: RoutingMode.fullTunnel,
                          label: Text('Exclusive'),
                        ),
                      ],
                      selected: {widget.routingMode},
                      onSelectionChanged: widget.routingLocked
                          ? null
                          : (next) {
                              if (next.isEmpty) return;
                              widget.onRoutingModeChanged(next.first);
                            },
                    ),
                  ),
                  if (!allAppsVpn) ...[
                    const SizedBox(height: 16),
                    ListTile(
                      enabled: !widget.routingLocked,
                      contentPadding: EdgeInsets.zero,
                      leading: Icon(
                        Icons.tune,
                        color: widget.colorScheme.onSurfaceVariant,
                      ),
                      title: _cardTitle('Select apps'),
                      subtitle: _cardText(
                        perAppSubtitle,
                        color: widget.allowedAppPackages.isEmpty
                            ? widget.colorScheme.error
                            : widget.colorScheme.onSurfaceVariant,
                      ),
                      trailing: Icon(
                        Icons.chevron_right,
                        color: widget.colorScheme.onSurfaceVariant,
                      ),
                      onTap: widget.onChooseApps,
                    ),
                  ],
                ],
              ),
            ),
          ),
        ],
        _sectionTitle('Local proxy'),
        const SizedBox(height: 8),
        Card(
          margin: EdgeInsets.zero,
          clipBehavior: Clip.antiAlias,
          child: Padding(
            padding: _kCardContentPadding,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                TextFormField(
                  key: const Key('proxy_http_port_field'),
                  controller: _httpPortController,
                  enabled: !widget.routingLocked,
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(labelText: 'HTTP port'),
                  onChanged: (value) => widget.onProxySettingsChanged(
                    widget.proxySettings.copyWith(
                      httpPort: ProxySettings.portFromText(
                        value,
                        fallback: widget.proxySettings.httpPort,
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                TextFormField(
                  key: const Key('proxy_socks_port_field'),
                  controller: _socksPortController,
                  enabled: !widget.routingLocked,
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(labelText: 'SOCKS5 port'),
                  onChanged: (value) => widget.onProxySettingsChanged(
                    widget.proxySettings.copyWith(
                      socksPort: ProxySettings.portFromText(
                        value,
                        fallback: widget.proxySettings.socksPort,
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 12),
                SwitchListTile(
                  key: const Key('proxy_allow_lan_switch'),
                  contentPadding: EdgeInsets.zero,
                  title: _cardTitle('Allow connections from LAN'),
                  subtitle: _cardText(
                    widget.proxySettings.allowLanConnections
                        ? 'Detect a shareable local IPv4 and expose both listeners to devices on the same Wi-Fi or hotspot when available.'
                        : 'Keep both listeners on this device only.',
                    color: widget.colorScheme.onSurfaceVariant,
                  ),
                  value: widget.proxySettings.allowLanConnections,
                  onChanged: widget.routingLocked
                      ? null
                      : (value) => widget.onProxySettingsChanged(
                          widget.proxySettings.copyWith(
                            allowLanConnections: value,
                          ),
                        ),
                ),
                if (widget.proxySettings.httpPort ==
                    widget.proxySettings.socksPort)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      'HTTP and SOCKS5 ports must be different before connecting.',
                      style: widget.textTheme.bodySmall?.copyWith(
                        color: widget.colorScheme.error,
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 12),
        Card(
          margin: EdgeInsets.zero,
          child: ListTile(
            contentPadding: _kCardTilePadding,
            leading: Icon(Icons.info_outline, color: semanticColors.info),
            title: _cardTitle('Proxy endpoints'),
            subtitle: _cardText(
              [
                'HTTP: $proxyDisplayHost:$proxyHttpPort',
                'SOCKS5: $proxyDisplayHost:$proxySocksPort',
                proxyEndpointStatus,
              ].join('\n'),
            ),
          ),
        ),
        const SizedBox(height: 22),
        _sectionTitle('Connectivity check'),
        const SizedBox(height: 8),
        Card(
          margin: EdgeInsets.zero,
          clipBehavior: Clip.antiAlias,
          child: Padding(
            padding: _kCardContentPadding,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _cardTitle('Status check URL'),
                _cardText(
                  'Used for the status check after you connect. Tap the badge anytime to refresh.',
                  color: widget.colorScheme.onSurfaceVariant,
                ),
                const SizedBox(height: 14),
                TextFormField(
                  key: const Key('connectivity_url_field'),
                  controller: _connectivityUrlController,
                  keyboardType: TextInputType.url,
                  decoration: InputDecoration(
                    labelText: 'Connectivity URL',
                    hintText: ConnectivityCheckSettings.defaultUrl,
                    errorText: _connectivityUrlError,
                  ),
                  onChanged: (value) {
                    final error = ConnectivityCheckSettings.validateUrl(value);
                    if (_connectivityUrlError != error) {
                      setState(() => _connectivityUrlError = error);
                    }
                    if (error != null) return;
                    widget.onConnectivityCheckSettingsChanged(
                      widget.connectivityCheckSettings.copyWith(url: value),
                    );
                  },
                ),
                const SizedBox(height: 14),
                TextFormField(
                  key: const Key('connectivity_timeout_field'),
                  controller: _connectivityTimeoutController,
                  keyboardType: TextInputType.number,
                  inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                  decoration: InputDecoration(
                    labelText: 'Connectivity timeout (ms)',
                    hintText: '${ConnectivityCheckSettings.defaultTimeoutMs}',
                    errorText: _connectivityTimeoutError,
                  ),
                  onChanged: (value) {
                    final error = ConnectivityCheckSettings.validateTimeoutMs(
                      value,
                    );
                    if (_connectivityTimeoutError != error) {
                      setState(() => _connectivityTimeoutError = error);
                    }
                    if (error != null) return;
                    final timeoutMs = ConnectivityCheckSettings.parseTimeoutMs(
                      value,
                    );
                    if (timeoutMs == null) return;
                    widget.onConnectivityCheckSettingsChanged(
                      widget.connectivityCheckSettings.copyWith(
                        timeoutMs: timeoutMs,
                      ),
                    );
                  },
                ),
                _cardText(
                  'Maximum time to wait before marking the check unreachable.',
                  color: widget.colorScheme.onSurfaceVariant,
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _sectionTitle(String text) {
    return Text(
      text,
      style: widget.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w600),
    );
  }

  Widget _cardTitle(String text) {
    return Text(
      text,
      style: widget.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w600),
    );
  }

  Widget _cardText(String text, {Color? color}) {
    return Padding(
      padding: const EdgeInsets.only(top: 4),
      child: Text(
        text,
        style: widget.textTheme.bodySmall?.copyWith(color: color),
      ),
    );
  }
}
