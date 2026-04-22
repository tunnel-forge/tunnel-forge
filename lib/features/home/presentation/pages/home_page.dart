import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:get_it/get_it.dart';

import '../../../../app_scaffold_messenger.dart';
import '../../../../app_selector_page.dart';
import '../../../../connectivity_checker.dart';
import '../../../../profile_models.dart';
import '../../../../profile_picker_sheet.dart';
import '../../../../profile_store.dart';
import '../../../../utils/log_entry.dart';
import '../../../../widgets/connection_panel.dart';
import '../../../../widgets/logs_panel.dart';
import '../../../../widgets/settings_panel.dart';
import '../../../app_theme/presentation/bloc/app_theme_bloc.dart';
import '../../../home/domain/home_models.dart';
import '../../../home/domain/home_repositories.dart';
import '../bloc/connectivity_bloc.dart';
import '../bloc/home_nav_bloc.dart';
import '../bloc/logs_bloc.dart';
import '../bloc/profiles_bloc.dart';
import '../bloc/settings_bloc.dart';
import '../bloc/tunnel_bloc.dart';

class VpnHomePage extends StatelessWidget {
  const VpnHomePage({super.key, required this.locator});

  final GetIt locator;

  @override
  Widget build(BuildContext context) {
    return MultiBlocProvider(
      providers: [
        BlocProvider<HomeNavBloc>(create: (_) => locator<HomeNavBloc>()),
        BlocProvider<ProfilesBloc>(
          create: (_) => locator<ProfilesBloc>()..add(const ProfilesStarted()),
        ),
        BlocProvider<SettingsBloc>(
          create: (_) => locator<SettingsBloc>()..add(const SettingsStarted()),
        ),
        BlocProvider<LogsBloc>(
          create: (_) => locator<LogsBloc>()..add(const LogsStarted()),
        ),
        BlocProvider<TunnelBloc>(
          create: (_) => locator<TunnelBloc>()..add(const TunnelStarted()),
        ),
        BlocProvider<ConnectivityBloc>(
          create: (_) => locator<ConnectivityBloc>(),
        ),
      ],
      child: _VpnHomePageView(locator: locator),
    );
  }
}

class _VpnHomePageView extends StatefulWidget {
  const _VpnHomePageView({required this.locator});

  final GetIt locator;

  @override
  State<_VpnHomePageView> createState() => _VpnHomePageViewState();
}

class _VpnHomePageViewState extends State<_VpnHomePageView> {
  final ScrollController _logsScroll = ScrollController();
  bool _logsStickToBottom = true;
  int _lastProfilesMessageId = 0;
  int _lastTunnelMessageId = 0;
  bool _lastTunnelUp = false;
  int _lastLogsEntryCount = 0;

  @override
  void initState() {
    super.initState();
    _logsScroll.addListener(_syncLogsStickToBottom);
  }

  @override
  void dispose() {
    _logsScroll.removeListener(_syncLogsStickToBottom);
    _logsScroll.dispose();
    super.dispose();
  }

  void _syncLogsStickToBottom() {
    if (!_logsScroll.hasClients) return;
    final metrics = _logsScroll.position;
    const edge = 88.0;
    final atBottom = metrics.pixels >= metrics.maxScrollExtent - edge;
    if (atBottom == _logsStickToBottom) return;
    setState(() => _logsStickToBottom = atBottom);
  }

  void _scheduleScrollLogsToEnd() {
    if (!_logsStickToBottom) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_logsScroll.hasClients) return;
      _logsScroll.position.jumpTo(_logsScroll.position.maxScrollExtent);
    });
  }

  void _jumpLogsToBottom() {
    setState(() => _logsStickToBottom = true);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_logsScroll.hasClients) return;
      _logsScroll.animateTo(
        _logsScroll.position.maxScrollExtent,
        duration: const Duration(milliseconds: 220),
        curve: Curves.easeOutCubic,
      );
    });
  }

  void _toast(String text, {bool error = false}) {
    showAppSnackBar(context, text, error: error);
  }

  Future<void> _handleProfilesStateChange(ProfilesState state) async {
    final message = state.message;
    if (message != null && message.id != _lastProfilesMessageId) {
      _lastProfilesMessageId = message.id;
      _toast(message.text, error: message.error);
    }
  }

  ConnectivityPingRequest _connectivityPingRequest(
    SettingsState settingsState,
    TunnelState tunnelState,
  ) {
    final url = settingsState.connectivityCheckSettings.url;
    if (settingsState.connectionMode == ConnectionMode.proxyOnly) {
      final proxyPort = tunnelState.proxyExposure?.httpPort;
      return ConnectivityPingRequest.localHttpProxy(
        url: url,
        proxyPort: ProxySettings.normalizePort(
          proxyPort ?? settingsState.proxySettings.httpPort,
          fallback: ProxySettings.defaultHttpPort,
        ),
      );
    }
    return ConnectivityPingRequest.direct(url);
  }

  Future<void> _handleTunnelStateChange(TunnelState current) async {
    final message = current.message;
    if (message != null && message.id != _lastTunnelMessageId) {
      _lastTunnelMessageId = message.id;
      _toast(message.text, error: message.error);
    }
    final settingsState = context.read<SettingsBloc>().state;
    if (!_lastTunnelUp && current.tunnelUp && !current.stopRequested) {
      context.read<ConnectivityBloc>().add(
        ConnectivityRunRequested(
          _connectivityPingRequest(settingsState, current),
        ),
      );
    } else if (_lastTunnelUp && !current.tunnelUp) {
      context.read<ConnectivityBloc>().add(const ConnectivityResetRequested());
    }
    context.read<ProfilesBloc>().add(
      ProfilesImportSelectionPolicyChanged(
        !current.busy &&
            !current.stopRequested &&
            !current.tunnelUp &&
            !current.awaitingTunnel,
      ),
    );
    _lastTunnelUp = current.tunnelUp;
  }

  Future<void> _handleLogsStateChange(LogsState current) async {
    if (_lastLogsEntryCount != current.entries.length) {
      _lastLogsEntryCount = current.entries.length;
      _scheduleScrollLogsToEnd();
    }
  }

  Future<void> _openProfilePicker() async {
    final profilesBloc = context.read<ProfilesBloc>();
    final profilesState = profilesBloc.state;
    if (profilesState.loading || !mounted) return;
    await ProfilePickerSheet.show(
      context,
      profilesBloc: profilesBloc,
      store: widget.locator<ProfileStore>(),
    );
  }

  Future<void> _pickAppsForVpn() async {
    final current = context.read<SettingsBloc>().state.allowedAppPackages;
    final repository = widget.locator<TunnelRepository>();
    final picked = await Navigator.of(context, rootNavigator: true)
        .push<Set<String>>(
          MaterialPageRoute(
            fullscreenDialog: true,
            builder: (ctx) => AppSelectorPage(
              initialSelection: Set<String>.from(current),
              loadApps: repository.listVpnCandidateApps,
              loadIcon: repository.getAppIcon,
            ),
          ),
        );
    if (!mounted || picked == null) return;
    context.read<SettingsBloc>().add(
      SettingsAllowedAppsChanged(picked.toList()..sort()),
    );
  }

  void _handleMissingProfileTap(ProfilesState profilesState) {
    final message = profilesState.profiles.isEmpty
        ? 'Create a profile first. You will need a server, username, and tunnel settings before connecting.'
        : 'Choose one of your saved profiles before connecting.';
    _toast(message, error: true);
    widget.locator<LogsRepository>().append(
      LogEntry(
        timestamp: DateTime.now(),
        level: LogLevel.warning,
        source: LogSource.dart,
        tag: 'tunnel',
        message: 'Connect blocked: no active profile',
      ),
    );
  }

  void _primaryAction(
    ProfilesState profilesState,
    SettingsState settingsState,
    TunnelState tunnelState,
  ) {
    if (tunnelState.busy || tunnelState.stopRequested) {
      return;
    }
    if (tunnelState.tunnelUp ||
        (tunnelState.awaitingTunnel && !tunnelState.tunnelUp)) {
      context.read<TunnelBloc>().add(const TunnelDisconnectRequested());
      return;
    }
    final row = profilesState.activeProfileRow;
    if (row == null || !profilesState.hasActiveProfile) {
      _handleMissingProfileTap(profilesState);
      return;
    }
    final profile = row.profile;
    final trimmedName = profile.displayName.trim();
    final dnsServers = profile.dnsAutomatic
        ? const <DnsServerConfig>[]
        : Profile.orderedDnsServers(
            dns1Host: profile.dns1Host,
            dns1Protocol: profile.dns1Protocol,
            dns2Host: profile.dns2Host,
            dns2Protocol: profile.dns2Protocol,
          );
    context.read<TunnelBloc>().add(
      TunnelConnectRequested(
        TunnelConnectRequest(
          activeProfileId: profile.id,
          profileName: trimmedName.isEmpty ? null : trimmedName,
          server: profile.server,
          user: profile.user,
          password: row.password,
          psk: row.psk,
          dnsAutomatic: profile.dnsAutomatic,
          dnsServers: dnsServers,
          mtu: profile.mtu,
          connectionMode: settingsState.connectionMode,
          routingMode: settingsState.routingMode,
          allowedAppPackages: settingsState.allowedAppPackages,
          proxySettings: settingsState.proxySettings,
        ),
      ),
    );
  }

  Future<void> _copyLogs(LogsState logsState) async {
    final visibleLogs = logsState.visibleLogs;
    if (visibleLogs.isEmpty) {
      _toast(
        logsState.entries.isEmpty
            ? 'No logs to copy'
            : 'No visible logs to copy',
      );
      return;
    }
    await Clipboard.setData(
      ClipboardData(
        text: visibleLogs.map((entry) => entry.toPlainText()).join('\n'),
      ),
    );
    final count = visibleLogs.length;
    _toast('Copied $count ${count == 1 ? 'line' : 'lines'} to clipboard');
  }

  void _clearLogs() {
    context.read<LogsBloc>().add(const LogsCleared());
    setState(() => _logsStickToBottom = true);
    _toast('Logs cleared');
  }

  String _connectButtonLabel(
    TunnelState tunnelState,
    bool hasActiveProfile,
    ConnectionMode connectionMode,
  ) {
    if (tunnelState.stopRequested && tunnelState.tunnelUp) {
      return 'Disconnecting...';
    }
    if (tunnelState.stopRequested) return 'Canceling...';
    if (tunnelState.busy && tunnelState.tunnelUp) return 'Disconnecting...';
    if (tunnelState.awaitingTunnel && !tunnelState.tunnelUp) {
      return 'Connecting... tap to cancel';
    }
    if (tunnelState.busy) return 'Working...';
    if (tunnelState.tunnelUp) {
      return connectionMode == ConnectionMode.proxyOnly
          ? 'Proxy ready'
          : 'Connected';
    }
    if (!hasActiveProfile) return 'Select profile';
    return 'Ready';
  }

  @override
  Widget build(BuildContext context) {
    final navState = context.watch<HomeNavBloc>().state;
    final profilesState = context.watch<ProfilesBloc>().state;
    final settingsState = context.watch<SettingsBloc>().state;
    final tunnelState = context.watch<TunnelBloc>().state;
    final logsState = context.watch<LogsBloc>().state;
    final connectivityState = context.watch<ConnectivityBloc>().state;
    final appThemeState = context.watch<AppThemeBloc>().state;

    final effectiveProfileId = profilesState.hasActiveProfile
        ? profilesState.activeProfileId
        : null;
    Profile? activeProfile;
    if (effectiveProfileId != null) {
      for (final profile in profilesState.profiles) {
        if (profile.id == effectiveProfileId) {
          activeProfile = profile;
          break;
        }
      }
    }
    final profileSummaryTitle = activeProfile != null
        ? activeProfile.displayName
        : (profilesState.profiles.isEmpty
              ? 'No saved profile'
              : 'Quick connect');
    final profileSummarySubtitle = activeProfile != null
        ? activeProfile.server
        : (profilesState.profiles.isEmpty
              ? 'Create your first profile'
              : 'Select a saved profile');
    final appBarTitle = switch (navState.index) {
      0 => 'Tunnel Forge',
      1 => 'Logs',
      _ => 'Settings',
    };

    return MultiBlocListener(
      listeners: [
        BlocListener<ProfilesBloc, ProfilesState>(
          listenWhen: (previous, current) =>
              previous.message != current.message,
          listener: (context, state) => _handleProfilesStateChange(state),
        ),
        BlocListener<TunnelBloc, TunnelState>(
          listenWhen: (previous, current) =>
              previous.message != current.message ||
              previous.tunnelUp != current.tunnelUp ||
              previous.busy != current.busy ||
              previous.awaitingTunnel != current.awaitingTunnel ||
              previous.stopRequested != current.stopRequested,
          listener: (context, state) => _handleTunnelStateChange(state),
        ),
        BlocListener<LogsBloc, LogsState>(
          listenWhen: (previous, current) =>
              previous.entries.length != current.entries.length,
          listener: (context, state) => _handleLogsStateChange(state),
        ),
      ],
      child: Scaffold(
        appBar: AppBar(
          title: AnimatedSwitcher(
            duration: const Duration(milliseconds: 360),
            switchInCurve: Curves.easeOutCubic,
            switchOutCurve: Curves.easeInCubic,
            transitionBuilder: (child, animation) {
              final curved = CurvedAnimation(
                parent: animation,
                curve: Curves.easeOutCubic,
              );
              return FadeTransition(
                opacity: curved,
                child: SlideTransition(
                  position: Tween<Offset>(
                    begin: const Offset(0, -0.18),
                    end: Offset.zero,
                  ).animate(curved),
                  child: ScaleTransition(
                    scale: Tween<double>(begin: 0.98, end: 1).animate(curved),
                    child: child,
                  ),
                ),
              );
            },
            child: Text(appBarTitle, key: ValueKey<String>(appBarTitle)),
          ),
          actions: [
            if (navState.index == 1)
              Padding(
                padding: const EdgeInsets.only(right: 4),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    PopupMenuButton<LogDisplayLevel>(
                      tooltip: 'Log level',
                      initialValue: logsState.level,
                      onSelected: (level) => context.read<LogsBloc>().add(
                        LogsLevelChangeRequested(level),
                      ),
                      itemBuilder: (context) => const [
                        PopupMenuItem<LogDisplayLevel>(
                          value: LogDisplayLevel.info,
                          child: Text('INFO'),
                        ),
                        PopupMenuItem<LogDisplayLevel>(
                          value: LogDisplayLevel.warning,
                          child: Text('WARNING'),
                        ),
                        PopupMenuItem<LogDisplayLevel>(
                          value: LogDisplayLevel.error,
                          child: Text('ERROR'),
                        ),
                        PopupMenuItem<LogDisplayLevel>(
                          value: LogDisplayLevel.debug,
                          child: Text('DEBUG'),
                        ),
                      ],
                      child: Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 8,
                          vertical: 8,
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            const Icon(Icons.filter_list_rounded, size: 20),
                            const SizedBox(width: 6),
                            Text(
                              logsState.levelLabel,
                              style: Theme.of(context).textTheme.labelMedium
                                  ?.copyWith(
                                    color: Theme.of(
                                      context,
                                    ).colorScheme.onSurfaceVariant,
                                  ),
                            ),
                          ],
                        ),
                      ),
                    ),
                    IconButton(
                      tooltip: logsState.wordWrap
                          ? 'Turn off word wrap (wide lines scroll sideways)'
                          : 'Turn on word wrap',
                      onPressed: () => context.read<LogsBloc>().add(
                        const LogsWordWrapToggled(),
                      ),
                      icon: Icon(
                        logsState.wordWrap ? Icons.wrap_text : Icons.swap_horiz,
                      ),
                    ),
                    IconButton(
                      tooltip: 'Copy visible',
                      onPressed: logsState.visibleLogs.isEmpty
                          ? null
                          : () => _copyLogs(logsState),
                      icon: const Icon(Icons.copy_all_outlined),
                    ),
                    IconButton(
                      tooltip: 'Clear',
                      onPressed: logsState.entries.isEmpty ? null : _clearLogs,
                      icon: const Icon(Icons.delete_outline),
                    ),
                  ],
                ),
              ),
          ],
        ),
        body: Padding(
          padding: const EdgeInsets.only(top: 12),
          child: AnimatedSwitcher(
            duration: const Duration(milliseconds: 420),
            switchInCurve: Curves.easeOutCubic,
            switchOutCurve: Curves.easeInCubic,
            layoutBuilder: (currentChild, previousChildren) => Stack(
              fit: StackFit.expand,
              alignment: Alignment.topCenter,
              children: <Widget>[...previousChildren, ?currentChild],
            ),
            transitionBuilder: (child, animation) {
              final curved = CurvedAnimation(
                parent: animation,
                curve: Curves.easeOutCubic,
              );
              return FadeTransition(
                opacity: curved,
                child: SlideTransition(
                  position: Tween<Offset>(
                    begin: const Offset(0.08, 0),
                    end: Offset.zero,
                  ).animate(curved),
                  child: ScaleTransition(
                    scale: Tween<double>(begin: 0.985, end: 1).animate(curved),
                    child: child,
                  ),
                ),
              );
            },
            child: SizedBox.expand(
              key: ValueKey<int>(navState.index),
              child: switch (navState.index) {
                0 => Align(
                  alignment: Alignment.topCenter,
                  child: ConnectionPanel(
                    profilesLoading: profilesState.loading,
                    profileSummaryTitle: profileSummaryTitle,
                    profileSummarySubtitle: profileSummarySubtitle,
                    onOpenProfilePicker: _openProfilePicker,
                    busy: tunnelState.busy,
                    tunnelUp: tunnelState.tunnelUp,
                    awaitingTunnel: tunnelState.awaitingTunnel,
                    stopRequested: tunnelState.stopRequested,
                    canStartConnection: profilesState.hasActiveProfile,
                    connectButtonLabel: _connectButtonLabel(
                      tunnelState,
                      profilesState.hasActiveProfile,
                      settingsState.connectionMode,
                    ),
                    onPrimary: () => _primaryAction(
                      profilesState,
                      settingsState,
                      tunnelState,
                    ),
                    onUnavailablePrimaryTap: () =>
                        _handleMissingProfileTap(profilesState),
                    connectivityBadgeState: connectivityState.badgeState,
                    connectivityBadgeLabel: connectivityState.badgeLabel,
                    onConnectivityTap: () =>
                        context.read<ConnectivityBloc>().add(
                          ConnectivityRunRequested(
                            _connectivityPingRequest(
                              settingsState,
                              tunnelState,
                            ),
                          ),
                        ),
                    colorScheme: Theme.of(context).colorScheme,
                    textTheme: Theme.of(context).textTheme,
                  ),
                ),
                1 => LogsPanel(
                  logs: logsState.visibleLogs,
                  scrollController: _logsScroll,
                  colorScheme: Theme.of(context).colorScheme,
                  textTheme: Theme.of(context).textTheme,
                  stickToBottom: _logsStickToBottom,
                  onJumpToLatest: _jumpLogsToBottom,
                  wordWrap: logsState.wordWrap,
                  hasAnyLogs: logsState.entries.isNotEmpty,
                ),
                _ => SettingsPanel(
                  themeMode: appThemeState.themeMode,
                  onThemeModeChanged: (mode) =>
                      context.read<AppThemeBloc>().add(AppThemeChanged(mode)),
                  connectionMode: settingsState.connectionMode,
                  routingMode: settingsState.routingMode,
                  allowedAppPackages: settingsState.allowedAppPackages,
                  proxySettings: settingsState.proxySettings,
                  connectivityCheckSettings:
                      settingsState.connectivityCheckSettings,
                  onConnectionModeChanged: (mode) => context
                      .read<SettingsBloc>()
                      .add(SettingsConnectionModeChanged(mode)),
                  onRoutingModeChanged: (mode) => context
                      .read<SettingsBloc>()
                      .add(SettingsRoutingModeChanged(mode)),
                  onProxySettingsChanged: (settings) => context
                      .read<SettingsBloc>()
                      .add(SettingsProxySettingsChanged(settings)),
                  proxyExposure: tunnelState.proxyExposure,
                  onConnectivityCheckSettingsChanged: (settings) => context
                      .read<SettingsBloc>()
                      .add(SettingsConnectivityUrlChanged(settings.url)),
                  onChooseApps: _pickAppsForVpn,
                  routingLocked:
                      profilesState.loading ||
                      tunnelState.busy ||
                      tunnelState.stopRequested ||
                      tunnelState.tunnelUp ||
                      tunnelState.awaitingTunnel,
                  colorScheme: Theme.of(context).colorScheme,
                  textTheme: Theme.of(context).textTheme,
                ),
              },
            ),
          ),
        ),
        bottomNavigationBar: NavigationBar(
          selectedIndex: navState.index,
          onDestinationSelected: (index) {
            context.read<HomeNavBloc>().add(HomeNavChanged(index));
            if (index == 1) _scheduleScrollLogsToEnd();
          },
          backgroundColor: Theme.of(context).colorScheme.surface,
          surfaceTintColor: Colors.transparent,
          indicatorColor: Theme.of(context).colorScheme.secondaryContainer,
          height: 72,
          labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
          animationDuration: const Duration(milliseconds: 360),
          destinations: const [
            NavigationDestination(
              icon: Icon(Icons.vpn_key_outlined),
              selectedIcon: Icon(Icons.vpn_key),
              label: 'VPN',
            ),
            NavigationDestination(
              icon: Icon(Icons.article_outlined),
              selectedIcon: Icon(Icons.article),
              label: 'Logs',
            ),
            NavigationDestination(
              icon: Icon(Icons.settings_outlined),
              selectedIcon: Icon(Icons.settings),
              label: 'Settings',
            ),
          ],
        ),
      ),
    );
  }
}
