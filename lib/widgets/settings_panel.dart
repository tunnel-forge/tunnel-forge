import 'package:flutter/material.dart';

import '../profile_models.dart';

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
    required this.onConnectionModeChanged,
    required this.onRoutingModeChanged,
    required this.onProxySettingsChanged,
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
  final ValueChanged<ConnectionMode> onConnectionModeChanged;
  final ValueChanged<RoutingMode> onRoutingModeChanged;
  final ValueChanged<ProxySettings> onProxySettingsChanged;
  final VoidCallback onChooseApps;
  final bool routingLocked;
  final ColorScheme colorScheme;
  final TextTheme textTheme;

  @override
  State<SettingsPanel> createState() => _SettingsPanelState();
}

class _SettingsPanelState extends State<SettingsPanel> {
  late final TextEditingController _httpPortController;
  late final TextEditingController _socksPortController;

  @override
  void initState() {
    super.initState();
    _httpPortController = TextEditingController(
      text: '${widget.proxySettings.httpPort}',
    );
    _socksPortController = TextEditingController(
      text: '${widget.proxySettings.socksPort}',
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
  }

  @override
  void dispose() {
    _httpPortController.dispose();
    _socksPortController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final allAppsVpn = widget.routingMode == RoutingMode.fullTunnel;
    final perAppSubtitle = widget.allowedAppPackages.isEmpty
        ? 'No apps selected. Choose at least one app to connect.'
        : '${widget.allowedAppPackages.length} app${widget.allowedAppPackages.length == 1 ? '' : 's'} will use VPN';
    final proxyMode = widget.connectionMode == ConnectionMode.proxyOnly;

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
      children: [
        if (widget.themeMode != null && widget.onThemeModeChanged != null) ...[
          Text(
            'Appearance',
            style: widget.textTheme.titleSmall?.copyWith(
              fontWeight: FontWeight.w600,
            ),
          ),
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
        Text(
          'Connection mode',
          style: widget.textTheme.titleSmall?.copyWith(
            fontWeight: FontWeight.w600,
          ),
        ),
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
          Text(
            'Traffic routing',
            style: widget.textTheme.titleSmall?.copyWith(
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(height: 8),
          Card(
            margin: EdgeInsets.zero,
            clipBehavior: Clip.antiAlias,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                SwitchListTile(
                  title: const Text('VPN for all apps'),
                  subtitle: Text(
                    allAppsVpn
                        ? 'Recommended: route device traffic through the VPN.'
                        : 'Only selected apps use the VPN.',
                    style: widget.textTheme.bodySmall?.copyWith(
                      color: widget.colorScheme.onSurfaceVariant,
                    ),
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
                    leading: Icon(
                      Icons.tune,
                      color: widget.colorScheme.primary,
                    ),
                    title: const Text('Select apps'),
                    subtitle: Text(
                      perAppSubtitle,
                      style: widget.textTheme.bodySmall?.copyWith(
                        color: widget.allowedAppPackages.isEmpty
                            ? widget.colorScheme.error
                            : widget.colorScheme.onSurfaceVariant,
                      ),
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
          Text(
            'Proxy settings',
            style: widget.textTheme.titleSmall?.copyWith(
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(height: 8),
          Card(
            margin: EdgeInsets.zero,
            clipBehavior: Clip.antiAlias,
            child: Column(
              children: [
                SwitchListTile(
                  title: const Text('HTTP proxy'),
                  subtitle: Text(
                    'Apps that support manual HTTP proxy settings can use 127.0.0.1:${widget.proxySettings.httpPort}',
                    style: widget.textTheme.bodySmall?.copyWith(
                      color: widget.colorScheme.onSurfaceVariant,
                    ),
                  ),
                  value: widget.proxySettings.httpEnabled,
                  onChanged: widget.routingLocked
                      ? null
                      : (v) => widget.onProxySettingsChanged(
                          widget.proxySettings.copyWith(httpEnabled: v),
                        ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                  child: TextFormField(
                    key: const Key('proxy_http_port_field'),
                    controller: _httpPortController,
                    enabled:
                        !widget.routingLocked && widget.proxySettings.httpEnabled,
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
                  title: const Text('SOCKS5 proxy'),
                  subtitle: Text(
                    'Apps that support SOCKS5 can use 127.0.0.1:${widget.proxySettings.socksPort}',
                    style: widget.textTheme.bodySmall?.copyWith(
                      color: widget.colorScheme.onSurfaceVariant,
                    ),
                  ),
                  value: widget.proxySettings.socksEnabled,
                  onChanged: widget.routingLocked
                      ? null
                      : (v) => widget.onProxySettingsChanged(
                          widget.proxySettings.copyWith(socksEnabled: v),
                        ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                  child: TextFormField(
                    key: const Key('proxy_socks_port_field'),
                    controller: _socksPortController,
                    enabled:
                        !widget.routingLocked && widget.proxySettings.socksEnabled,
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
              leading: Icon(
                Icons.info_outline,
                color: widget.colorScheme.primary,
              ),
              title: const Text('Proxy endpoints'),
              subtitle: Text(
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
      ],
    );
  }
}
