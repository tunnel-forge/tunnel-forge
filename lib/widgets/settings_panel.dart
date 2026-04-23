import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../features/home/domain/home_models.dart';
import '../profile_models.dart';

/// Theme mode, connection mode, split tunneling, and global local-proxy settings.
class SettingsPanel extends StatefulWidget {
  const SettingsPanel({
    super.key,
    this.themeMode,
    this.onThemeModeChanged,
    required this.connectionMode,
    required this.splitTunnelSettings,
    required this.proxySettings,
    this.proxyExposure,
    required this.connectivityCheckSettings,
    required this.onConnectionModeChanged,
    required this.onSplitTunnelSettingsChanged,
    required this.onProxySettingsChanged,
    required this.onConnectivityCheckSettingsChanged,
    required this.onChooseApps,
    this.onOpenL2tpSecurityNotice,
    this.installedVersion,
    this.installedVersionError,
    required this.appUpdateStatus,
    this.latestReleaseVersion,
    this.updateErrorMessage,
    required this.onRefreshVersionCheck,
    this.onOpenReleasePage,
    required this.onOpenTelegram,
    required this.onOpenGithub,
    required this.routingLocked,
    required this.colorScheme,
    required this.textTheme,
  });

  final ThemeMode? themeMode;
  final ValueChanged<ThemeMode>? onThemeModeChanged;
  final ConnectionMode connectionMode;
  final SplitTunnelSettings splitTunnelSettings;
  final ProxySettings proxySettings;
  final ProxyExposure? proxyExposure;
  final ConnectivityCheckSettings connectivityCheckSettings;
  final ValueChanged<ConnectionMode> onConnectionModeChanged;
  final ValueChanged<SplitTunnelSettings> onSplitTunnelSettingsChanged;
  final ValueChanged<ProxySettings> onProxySettingsChanged;
  final ValueChanged<ConnectivityCheckSettings>
  onConnectivityCheckSettingsChanged;
  final VoidCallback onChooseApps;
  final VoidCallback? onOpenL2tpSecurityNotice;
  final String? installedVersion;
  final String? installedVersionError;
  final AppUpdateStatus appUpdateStatus;
  final String? latestReleaseVersion;
  final String? updateErrorMessage;
  final VoidCallback onRefreshVersionCheck;
  final VoidCallback? onOpenReleasePage;
  final VoidCallback onOpenTelegram;
  final VoidCallback onOpenGithub;
  final bool routingLocked;
  final ColorScheme colorScheme;
  final TextTheme textTheme;

  @override
  State<SettingsPanel> createState() => _SettingsPanelState();
}

class _SettingsPanelState extends State<SettingsPanel> {
  static const double _kSectionGap = 24;
  static const double _kSectionHeaderGap = 8;
  static const double _kCardGap = 12;
  static const EdgeInsets _kCardTilePadding = EdgeInsets.fromLTRB(
    16,
    12,
    16,
    12,
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
    final splitTunnelSettings = widget.splitTunnelSettings;
    final isInclusive = splitTunnelSettings.mode == SplitTunnelMode.inclusive;
    final activePackages = splitTunnelSettings.activePackages;
    final activePackagesEmpty = activePackages.isEmpty;
    final splitTunnelSubtitle = switch (splitTunnelSettings.mode) {
      SplitTunnelMode.inclusive when activePackagesEmpty =>
        'No apps selected. Choose at least one app to use the VPN.',
      SplitTunnelMode.inclusive =>
        '${activePackages.length} app${activePackages.length == 1 ? '' : 's'} will use the VPN.',
      SplitTunnelMode.exclusive when activePackagesEmpty =>
        'No apps selected. All apps will continue using the VPN.',
      SplitTunnelMode.exclusive =>
        '${activePackages.length} app${activePackages.length == 1 ? '' : 's'} will bypass the VPN.',
    };
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
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
      children: [
        if (widget.themeMode != null && widget.onThemeModeChanged != null) ...[
          _sectionTitle('Appearance'),
          const SizedBox(height: _kSectionHeaderGap),
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
          const SizedBox(height: _kSectionGap),
        ],
        _sectionTitle('Connection mode'),
        const SizedBox(height: _kSectionHeaderGap),
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
        const SizedBox(height: _kSectionGap),
        if (!proxyMode) ...[
          _sectionTitle('Split tunneling'),
          const SizedBox(height: _kSectionHeaderGap),
          Card(
            margin: EdgeInsets.zero,
            clipBehavior: Clip.antiAlias,
            child: Padding(
              padding: _kCardContentPadding,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SwitchListTile(
                    key: const Key('split_tunnel_enabled_switch'),
                    contentPadding: EdgeInsets.zero,
                    title: _cardTitle('Enable split tunneling'),
                    subtitle: _cardText(
                      splitTunnelSettings.enabled
                          ? 'Apply the selected app list using ${isInclusive ? 'inclusive' : 'exclusive'} mode.'
                          : 'Route all apps through the VPN and keep both app lists saved for later.',
                      color: widget.colorScheme.onSurfaceVariant,
                    ),
                    value: splitTunnelSettings.enabled,
                    onChanged: widget.routingLocked
                        ? null
                        : (value) => widget.onSplitTunnelSettingsChanged(
                            splitTunnelSettings.copyWith(enabled: value),
                          ),
                  ),
                  if (splitTunnelSettings.enabled) ...[
                    const SizedBox(height: 8),
                    _cardTitle('Mode'),
                    _cardText(
                      isInclusive
                          ? 'Inclusive routes only the apps you choose through the VPN.'
                          : 'Exclusive routes all apps through the VPN except the apps you choose.',
                      color: widget.colorScheme.onSurfaceVariant,
                    ),
                    const SizedBox(height: 14),
                    SizedBox(
                      width: double.infinity,
                      child: SegmentedButton<SplitTunnelMode>(
                        showSelectedIcon: false,
                        segments: const [
                          ButtonSegment<SplitTunnelMode>(
                            value: SplitTunnelMode.inclusive,
                            label: Text('Inclusive'),
                          ),
                          ButtonSegment<SplitTunnelMode>(
                            value: SplitTunnelMode.exclusive,
                            label: Text('Exclusive'),
                          ),
                        ],
                        selected: {splitTunnelSettings.mode},
                        onSelectionChanged: widget.routingLocked
                            ? null
                            : (next) {
                                if (next.isEmpty) return;
                                widget.onSplitTunnelSettingsChanged(
                                  splitTunnelSettings.copyWith(
                                    mode: next.first,
                                  ),
                                );
                              },
                      ),
                    ),
                    const SizedBox(height: 16),
                    ListTile(
                      enabled: !widget.routingLocked,
                      contentPadding: EdgeInsets.zero,
                      leading: Icon(
                        isInclusive
                            ? Icons.arrow_downward_rounded
                            : Icons.arrow_outward_rounded,
                        color: widget.colorScheme.onSurfaceVariant,
                      ),
                      title: _cardTitle(
                        isInclusive
                            ? 'Select apps using VPN'
                            : 'Select apps outside VPN',
                      ),
                      subtitle: _cardText(
                        splitTunnelSubtitle,
                        color: isInclusive && activePackagesEmpty
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
          const SizedBox(height: _kSectionGap),
        ],
        _sectionTitle('Local proxy'),
        const SizedBox(height: _kSectionHeaderGap),
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
        const SizedBox(height: _kCardGap),
        Card(
          margin: EdgeInsets.zero,
          child: Padding(
            padding: _kCardTilePadding,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _cardTitle('Proxy endpoints'),
                const SizedBox(height: 8),
                _cardText(
                  [
                    'HTTP: $proxyDisplayHost:$proxyHttpPort',
                    'SOCKS5: $proxyDisplayHost:$proxySocksPort',
                    proxyEndpointStatus,
                  ].join('\n'),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: _kSectionGap),
        _sectionTitle('Connectivity check'),
        const SizedBox(height: _kSectionHeaderGap),
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
        if (widget.onOpenL2tpSecurityNotice != null) ...[
          const SizedBox(height: _kSectionGap),
          _sectionTitle('Notice'),
          const SizedBox(height: _kSectionHeaderGap),
          Card(
            margin: EdgeInsets.zero,
            child: ListTile(
              key: const Key('l2tp_security_notice_tile'),
              contentPadding: _kCardTilePadding,
              title: _cardTitle('L2TP security notice'),
              subtitle: _cardText(
                'Review the L2TP/IPsec compatibility notice.',
                color: widget.colorScheme.onSurfaceVariant,
              ),
              trailing: Icon(
                Icons.chevron_right,
                color: widget.colorScheme.onSurfaceVariant,
              ),
              onTap: widget.onOpenL2tpSecurityNotice,
            ),
          ),
        ],
        const SizedBox(height: _kSectionGap),
        _sectionTitle('Updates'),
        const SizedBox(height: _kSectionHeaderGap),
        Card(
          key: const Key('settings_update_card'),
          margin: EdgeInsets.zero,
          child: Padding(
            padding: _kCardContentPadding,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _cardTitle(_updateTitle()),
                _cardText(
                  _updateSubtitle(),
                  color: widget.colorScheme.onSurfaceVariant,
                ),
                const SizedBox(height: 12),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    OutlinedButton.icon(
                      key: const Key('settings_update_refresh_button'),
                      onPressed: widget.onRefreshVersionCheck,
                      icon: const Icon(Icons.refresh),
                      label: const Text('Refresh'),
                    ),
                    if (widget.onOpenReleasePage != null)
                      FilledButton.tonalIcon(
                        key: const Key('settings_update_open_button'),
                        onPressed: widget.onOpenReleasePage,
                        icon: const Icon(Icons.open_in_new),
                        label: const Text('Open release page'),
                      ),
                  ],
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: _kSectionGap),
        Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: Text(
            widget.installedVersion == null
                ? 'Version unavailable'
                : 'Version ${widget.installedVersion}',
            key: const Key('settings_version_footer'),
            textAlign: TextAlign.center,
            style: widget.textTheme.bodySmall?.copyWith(
              color: widget.colorScheme.onSurfaceVariant,
            ),
          ),
        ),
        _sectionTitle('About'),
        const SizedBox(height: _kSectionHeaderGap),
        Card(
          key: const Key('settings_about_card'),
          margin: EdgeInsets.zero,
          clipBehavior: Clip.antiAlias,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(
                padding: _kCardContentPadding,
                child: _cardText(
                  'Tunnel Forge is a simple Android app for L2TP/IPsec VPN, local proxy access, and per-app routing.',
                  color: widget.colorScheme.onSurfaceVariant,
                ),
              ),
              const Divider(height: 1),
              ListTile(
                key: const Key('settings_about_telegram_tile'),
                contentPadding: _kCardTilePadding,
                title: _cardTitle('Telegram'),
                subtitle: _cardText(
                  'https://t.me/TunnelForge',
                  color: widget.colorScheme.onSurfaceVariant,
                ),
                trailing: Icon(
                  Icons.open_in_new,
                  color: widget.colorScheme.onSurfaceVariant,
                ),
                onTap: widget.onOpenTelegram,
              ),
              const Divider(height: 1),
              ListTile(
                key: const Key('settings_about_github_tile'),
                contentPadding: _kCardTilePadding,
                title: _cardTitle('GitHub'),
                subtitle: _cardText(
                  'https://github.com/evokelektrique/tunnel-forge',
                  color: widget.colorScheme.onSurfaceVariant,
                ),
                trailing: Icon(
                  Icons.open_in_new,
                  color: widget.colorScheme.onSurfaceVariant,
                ),
                onTap: widget.onOpenGithub,
              ),
            ],
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

  String _updateTitle() {
    return switch (widget.appUpdateStatus) {
      AppUpdateStatus.loading => 'Checking for updates',
      AppUpdateStatus.updateAvailable => 'Update available',
      AppUpdateStatus.aheadOfRelease => 'Installed build is newer',
      AppUpdateStatus.comparisonUnavailable => 'Installed version unavailable',
      AppUpdateStatus.error => 'Update check unavailable',
      AppUpdateStatus.upToDate => 'App is up to date',
      AppUpdateStatus.idle => 'Check for updates',
    };
  }

  String _updateSubtitle() {
    final latestRelease = widget.latestReleaseVersion;
    return switch (widget.appUpdateStatus) {
      AppUpdateStatus.loading =>
        'Looking up the latest published GitHub release.',
      AppUpdateStatus.updateAvailable when latestRelease != null =>
        'Latest release: $latestRelease. You are running ${widget.installedVersion ?? 'an unknown build'}.',
      AppUpdateStatus.upToDate when latestRelease != null =>
        'You are running the latest published release: $latestRelease.',
      AppUpdateStatus.aheadOfRelease when latestRelease != null =>
        'This build is newer than the latest GitHub release ($latestRelease).',
      AppUpdateStatus.comparisonUnavailable when latestRelease != null =>
        'Latest release: $latestRelease. ${widget.updateErrorMessage ?? widget.installedVersionError ?? 'Installed version unavailable, so this build cannot be compared.'}',
      AppUpdateStatus.error =>
        widget.updateErrorMessage ?? 'Couldn\'t check for updates right now.',
      _ => 'Check GitHub releases to see whether a newer build is available.',
    };
  }
}
