import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'package:tunnel_forge/features/home/domain/home_models.dart';
import 'package:tunnel_forge/l10n/app_localizations.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';

/// Theme mode, connection mode, split tunneling, and global local-proxy settings.
class SettingsPanel extends StatefulWidget {
  const SettingsPanel({
    super.key,
    required this.language,
    required this.onLanguageChanged,
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

  final AppLanguage language;
  final ValueChanged<AppLanguage> onLanguageChanged;
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
    final t = AppLocalizations.of(context);
    final isInclusive = splitTunnelSettings.mode == SplitTunnelMode.inclusive;
    final activePackages = splitTunnelSettings.activePackages;
    final activePackagesEmpty = activePackages.isEmpty;
    final splitTunnelSubtitle = switch (splitTunnelSettings.mode) {
      SplitTunnelMode.inclusive when activePackagesEmpty => t.noAppsUseVpn,
      SplitTunnelMode.inclusive => t.appsWillUseVpn(activePackages.length),
      SplitTunnelMode.exclusive when activePackagesEmpty => t.noAppsBypassVpn,
      SplitTunnelMode.exclusive => t.appsWillBypassVpn(activePackages.length),
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
      ProxyExposure exposure when exposure.lanActive => t.lanClientsCanUse,
      ProxyExposure exposure when exposure.lanRequested => t.lanSharingEnabled,
      _ when widget.proxySettings.allowLanConnections => t.connectToDetectLan,
      _ => t.lanDisabled,
    };
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
      children: [
        _sectionTitle(t.languageLabel),
        const SizedBox(height: _kSectionHeaderGap),
        SegmentedButton<AppLanguage>(
          segments: [
            ButtonSegment<AppLanguage>(
              value: AppLanguage.english,
              label: Text(t.english),
            ),
            ButtonSegment<AppLanguage>(
              value: AppLanguage.persian,
              label: Text(t.persian),
            ),
          ],
          selected: {widget.language},
          onSelectionChanged: (next) {
            if (next.isEmpty) return;
            widget.onLanguageChanged(next.first);
          },
        ),
        const SizedBox(height: _kSectionGap),
        if (widget.themeMode != null && widget.onThemeModeChanged != null) ...[
          _sectionTitle(t.appearance),
          const SizedBox(height: _kSectionHeaderGap),
          SegmentedButton<ThemeMode>(
            segments: [
              ButtonSegment<ThemeMode>(
                value: ThemeMode.system,
                label: Text(t.system),
                icon: const Icon(Icons.brightness_auto_outlined, size: 18),
              ),
              ButtonSegment<ThemeMode>(
                value: ThemeMode.light,
                label: Text(t.light),
                icon: const Icon(Icons.light_mode_outlined, size: 18),
              ),
              ButtonSegment<ThemeMode>(
                value: ThemeMode.dark,
                label: Text(t.dark),
                icon: const Icon(Icons.dark_mode_outlined, size: 18),
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
        _sectionTitle(t.connectionMode),
        const SizedBox(height: _kSectionHeaderGap),
        SegmentedButton<ConnectionMode>(
          segments: [
            ButtonSegment<ConnectionMode>(
              value: ConnectionMode.vpnTunnel,
              label: Text(t.vpn),
              icon: const Icon(Icons.vpn_key_outlined, size: 18),
            ),
            ButtonSegment<ConnectionMode>(
              value: ConnectionMode.proxyOnly,
              label: Text(t.localProxy),
              icon: const Icon(Icons.lan_outlined, size: 18),
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
          _sectionTitle(t.splitTunneling),
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
                    title: _cardTitle(t.enableSplitTunneling),
                    subtitle: _cardText(
                      splitTunnelSettings.enabled
                          ? t.splitTunnelApply(
                              isInclusive ? t.inclusive : t.exclusive,
                            )
                          : t.splitTunnelRouteAll,
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
                    _cardTitle(t.mode),
                    _cardText(
                      isInclusive
                          ? t.inclusiveDescription
                          : t.exclusiveDescription,
                      color: widget.colorScheme.onSurfaceVariant,
                    ),
                    const SizedBox(height: 14),
                    SizedBox(
                      width: double.infinity,
                      child: SegmentedButton<SplitTunnelMode>(
                        showSelectedIcon: false,
                        segments: [
                          ButtonSegment<SplitTunnelMode>(
                            value: SplitTunnelMode.inclusive,
                            label: Text(t.inclusive),
                          ),
                          ButtonSegment<SplitTunnelMode>(
                            value: SplitTunnelMode.exclusive,
                            label: Text(t.exclusive),
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
                            ? t.selectAppsUsingVpn
                            : t.selectAppsOutsideVpn,
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
        _sectionTitle(t.localProxy),
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
                  decoration: InputDecoration(labelText: t.httpPort),
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
                  decoration: InputDecoration(labelText: t.socksPort),
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
                  title: _cardTitle(t.allowLan),
                  subtitle: _cardText(
                    widget.proxySettings.allowLanConnections
                        ? t.allowLanOn
                        : t.allowLanOff,
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
                      t.proxyPortsDifferent,
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
                _cardTitle(t.proxyEndpoints),
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
        _sectionTitle(t.connectivityCheck),
        const SizedBox(height: _kSectionHeaderGap),
        Card(
          margin: EdgeInsets.zero,
          clipBehavior: Clip.antiAlias,
          child: Padding(
            padding: _kCardContentPadding,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _cardTitle(t.statusCheckUrl),
                _cardText(
                  t.statusCheckHelp,
                  color: widget.colorScheme.onSurfaceVariant,
                ),
                const SizedBox(height: 14),
                TextFormField(
                  key: const Key('connectivity_url_field'),
                  controller: _connectivityUrlController,
                  keyboardType: TextInputType.url,
                  decoration: InputDecoration(
                    labelText: t.connectivityUrl,
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
                    labelText: t.connectivityTimeout,
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
                  t.timeoutHelp,
                  color: widget.colorScheme.onSurfaceVariant,
                ),
              ],
            ),
          ),
        ),
        if (widget.onOpenL2tpSecurityNotice != null) ...[
          const SizedBox(height: _kSectionGap),
          _sectionTitle(t.notice),
          const SizedBox(height: _kSectionHeaderGap),
          Card(
            margin: EdgeInsets.zero,
            child: ListTile(
              key: const Key('l2tp_security_notice_tile'),
              contentPadding: _kCardTilePadding,
              title: _cardTitle(t.l2tpSecurityNotice),
              subtitle: _cardText(
                t.reviewL2tpNotice,
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
        _sectionTitle(t.updates),
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
                      label: Text(t.refresh),
                    ),
                    if (widget.onOpenReleasePage != null)
                      FilledButton.tonalIcon(
                        key: const Key('settings_update_open_button'),
                        onPressed: widget.onOpenReleasePage,
                        icon: const Icon(Icons.open_in_new),
                        label: Text(t.openReleasePage),
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
                ? t.versionUnavailable
                : t.version(widget.installedVersion!),
            key: const Key('settings_version_footer'),
            textAlign: TextAlign.center,
            style: widget.textTheme.bodySmall?.copyWith(
              color: widget.colorScheme.onSurfaceVariant,
            ),
          ),
        ),
        _sectionTitle(t.about),
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
                  t.aboutText,
                  color: widget.colorScheme.onSurfaceVariant,
                ),
              ),
              const Divider(height: 1),
              ListTile(
                key: const Key('settings_about_telegram_tile'),
                contentPadding: _kCardTilePadding,
                title: _cardTitle(t.telegramTitle),
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
                title: _cardTitle(t.githubTitle),
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
    final t = AppText.current;
    return switch (widget.appUpdateStatus) {
      AppUpdateStatus.loading => t.checkingUpdates,
      AppUpdateStatus.updateAvailable => t.updateAvailable,
      AppUpdateStatus.aheadOfRelease => t.buildNewer,
      AppUpdateStatus.comparisonUnavailable => t.versionUnavailable,
      AppUpdateStatus.error => t.updateCheckUnavailable,
      AppUpdateStatus.upToDate => t.appUpToDate,
      AppUpdateStatus.idle => t.checkForUpdates,
    };
  }

  String _updateSubtitle() {
    final t = AppText.current;
    final latestRelease = widget.latestReleaseVersion;
    return switch (widget.appUpdateStatus) {
      AppUpdateStatus.loading => t.lookingUpRelease,
      AppUpdateStatus.updateAvailable when latestRelease != null =>
        t.latestReleaseRunning(
          latestRelease,
          widget.installedVersion ?? t.unknownBuild,
        ),
      AppUpdateStatus.upToDate when latestRelease != null => t.latestPublished(
        latestRelease,
      ),
      AppUpdateStatus.aheadOfRelease when latestRelease != null =>
        t.buildNewerThanLatest(latestRelease),
      AppUpdateStatus.comparisonUnavailable when latestRelease != null =>
        t.latestCannotCompare(
          latestRelease,
          t.updateCheckError(
            widget.updateErrorMessage ??
                widget.installedVersionError ??
                t.installedVersionUnavailableCompare,
          ),
        ),
      AppUpdateStatus.error =>
        widget.updateErrorMessage == null
        ? t.couldNotCheckUpdates
        : t.updateCheckError(widget.updateErrorMessage!),
      _ => t.checkGithubReleases,
    };
  }
}
