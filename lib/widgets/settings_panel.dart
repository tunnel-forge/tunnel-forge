import 'package:flutter/material.dart';

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
  String? _connectivityUrlError;

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
  }

  @override
  void dispose() {
    _httpPortController.dispose();
    _socksPortController.dispose();
    _connectivityUrlController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final allAppsVpn = widget.routingMode == RoutingMode.fullTunnel;
    final perAppSubtitle = widget.allowedAppPackages.isEmpty
        ? 'No apps selected. Choose at least one app to connect.'
        : '${widget.allowedAppPackages.length} app${widget.allowedAppPackages.length == 1 ? '' : 's'} will use VPN';
    final proxyMode = widget.connectionMode == ConnectionMode.proxyOnly;
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
          _sectionTitle('Traffic routing'),
          const SizedBox(height: 8),
          Card(
            margin: EdgeInsets.zero,
            clipBehavior: Clip.antiAlias,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                SwitchListTile(
                  contentPadding: _kCardTilePadding,
                  title: _cardTitle('VPN for all apps'),
                  subtitle: _cardText(
                    allAppsVpn
                        ? 'Route all device traffic through the VPN.'
                        : 'Only the apps you choose will use the VPN.',
                    color: widget.colorScheme.onSurfaceVariant,
                  ),
                  value: allAppsVpn,
                  onChanged: widget.routingLocked
                      ? null
                      : (v) => widget.onRoutingModeChanged(
                          v
                              ? RoutingMode.fullTunnel
                              : RoutingMode.perAppAllowList,
                        ),
                ),
                if (!allAppsVpn) ...[
                  const Divider(height: 1),
                  ListTile(
                    enabled: !widget.routingLocked,
                    contentPadding: _kCardTilePadding,
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
        ] else ...[
          _sectionTitle('Proxy settings'),
          const SizedBox(height: 8),
          Card(
            margin: EdgeInsets.zero,
            clipBehavior: Clip.antiAlias,
            child: Column(
              children: [
                SwitchListTile(
                  contentPadding: _kCardTilePadding,
                  title: _cardTitle('HTTP proxy'),
                  subtitle: _cardText(
                    'Apps that support manual HTTP proxy settings can use 127.0.0.1:${widget.proxySettings.httpPort}',
                    color: widget.colorScheme.onSurfaceVariant,
                  ),
                  value: widget.proxySettings.httpEnabled,
                  onChanged: widget.routingLocked
                      ? null
                      : (v) => widget.onProxySettingsChanged(
                          widget.proxySettings.copyWith(httpEnabled: v),
                        ),
                ),
                Padding(
                  padding: _kCardFieldPadding,
                  child: TextFormField(
                    key: const Key('proxy_http_port_field'),
                    controller: _httpPortController,
                    enabled:
                        !widget.routingLocked &&
                        widget.proxySettings.httpEnabled,
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
                ),
                const Divider(height: 1),
                SwitchListTile(
                  contentPadding: _kCardTilePadding,
                  title: _cardTitle('SOCKS5 proxy'),
                  subtitle: _cardText(
                    'Apps that support SOCKS5 can use 127.0.0.1:${widget.proxySettings.socksPort}',
                    color: widget.colorScheme.onSurfaceVariant,
                  ),
                  value: widget.proxySettings.socksEnabled,
                  onChanged: widget.routingLocked
                      ? null
                      : (v) => widget.onProxySettingsChanged(
                          widget.proxySettings.copyWith(socksEnabled: v),
                        ),
                ),
                Padding(
                  padding: _kCardFieldPadding,
                  child: TextFormField(
                    key: const Key('proxy_socks_port_field'),
                    controller: _socksPortController,
                    enabled:
                        !widget.routingLocked &&
                        widget.proxySettings.socksEnabled,
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
                ),
              ],
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
                  if (widget.proxySettings.httpEnabled)
                    'HTTP: 127.0.0.1:${widget.proxySettings.httpPort}',
                  if (widget.proxySettings.socksEnabled)
                    'SOCKS5: 127.0.0.1:${widget.proxySettings.socksPort}',
                  if (!widget.proxySettings.httpEnabled &&
                      !widget.proxySettings.socksEnabled)
                    'Enable at least one listener before connecting.',
                ].join('\n'),
              ),
            ),
          ),
        ],
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
                  'Set the address used for the status check after you connect. Tap the badge again anytime while connected to refresh it.',
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
