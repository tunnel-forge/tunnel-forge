part of '../home_page.dart';

/// Bottom nav: 0 VPN, 1 Logs, 2 Settings — animated app bar and tab bodies.
extension _VpnHomePageUi on _VpnHomePageState {
  Widget _buildMainTab(
    ColorScheme cs,
    TextTheme textTheme,
    String? effectiveProfileId,
  ) {
    Profile? activeProfile;
    if (effectiveProfileId != null) {
      for (final p in _profiles) {
        if (p.id == effectiveProfileId) {
          activeProfile = p;
          break;
        }
      }
    }
    final profileSummaryTitle = activeProfile != null
        ? activeProfile.displayName
        : (_profiles.isEmpty ? 'No saved profile' : 'Quick connect');
    final profileSummarySubtitle = activeProfile != null
        ? activeProfile.server
        : (_profiles.isEmpty
              ? 'Create your first profile'
              : 'Select a saved profile');

    switch (_navIndex) {
      case 0:
        return Align(
          alignment: Alignment.topCenter,
          child: ConnectionPanel(
            profilesLoading: _profilesLoading,
            profileSummaryTitle: profileSummaryTitle,
            profileSummarySubtitle: profileSummarySubtitle,
            onOpenProfilePicker: _openProfilePicker,
            busy: _busy,
            tunnelUp: _tunnelUp,
            awaitingTunnel: _awaitingTunnel,
            canStartConnection: _hasActiveProfile,
            connectButtonLabel: _connectButtonLabel,
            onPrimary: _primaryAction,
            onUnavailablePrimaryTap: _handleMissingProfileTap,
            connectivityBadgeState: _connectivityBadgeState,
            connectivityBadgeLabel: _connectivityBadgeLabel,
            onConnectivityTap: _runConnectivityCheck,
            colorScheme: cs,
            textTheme: textTheme,
          ),
        );
      case 1:
        return ListenableBuilder(
          listenable: _logBuffer,
          builder: (context, _) => LogsPanel(
            logs: _visibleLogs,
            scrollController: _logsScroll,
            colorScheme: cs,
            textTheme: textTheme,
            stickToBottom: _logsStickToBottom,
            onJumpToLatest: _jumpLogsToBottom,
            wordWrap: _logsWordWrap,
            hasAnyLogs: _logBuffer.entries.isNotEmpty,
            levelLabel: _logsLevelLabel,
          ),
        );
      default:
        return SettingsPanel(
          themeMode: widget.themeMode,
          onThemeModeChanged: widget.onThemeModeChanged,
          connectionMode: _connectionMode,
          routingMode: _routingMode,
          allowedAppPackages: _allowedAppPackages,
          proxySettings: _proxySettings,
          connectivityCheckSettings: _connectivityCheckSettings,
          onConnectionModeChanged: (mode) {
            _setConnectionMode(mode);
          },
          onRoutingModeChanged: (m) => _setHomeState(() => _routingMode = m),
          onProxySettingsChanged: (settings) {
            _setProxySettings(settings);
          },
          onConnectivityCheckSettingsChanged: (settings) {
            _setConnectivityCheckSettings(settings);
          },
          onChooseApps: _pickAppsForVpn,
          routingLocked:
              _profilesLoading || _busy || _tunnelUp || _awaitingTunnel,
          colorScheme: cs,
          textTheme: textTheme,
        );
    }
  }

  Widget _buildHomeScaffold(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;
    final effectiveProfileId = _hasActiveProfile ? _activeProfileId : null;
    final appBarTitle = switch (_navIndex) {
      0 => 'Tunnel Forge',
      1 => 'Logs',
      _ => 'Settings',
    };

    return Scaffold(
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
          AnimatedSwitcher(
            duration: const Duration(milliseconds: 260),
            switchInCurve: Curves.easeOut,
            switchOutCurve: Curves.easeIn,
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
                  child: child,
                ),
              );
            },
            child: Padding(
              key: ValueKey<int>(_navIndex),
              padding: const EdgeInsets.only(right: 4),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: switch (_navIndex) {
                  0 => <Widget>[],
                  1 => [
                    PopupMenuButton<LogDisplayLevel>(
                      tooltip: 'Log level',
                      initialValue: _logsLevel,
                      onSelected: (level) {
                        unawaited(_setLogsLevel(level));
                      },
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
                              _logsLevelLabel,
                              style: textTheme.labelMedium?.copyWith(
                                color: cs.onSurfaceVariant,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                    IconButton(
                      tooltip: _logsWordWrap
                          ? 'Turn off word wrap (wide lines scroll sideways)'
                          : 'Turn on word wrap',
                      onPressed: () =>
                          _setHomeState(() => _logsWordWrap = !_logsWordWrap),
                      icon: Icon(
                        _logsWordWrap ? Icons.wrap_text : Icons.swap_horiz,
                      ),
                    ),
                    ListenableBuilder(
                      listenable: _logBuffer,
                      builder: (context, _) {
                        final visibleEmpty = _visibleLogs.isEmpty;
                        final empty = _logBuffer.isEmpty;
                        return Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            IconButton(
                              tooltip: 'Copy visible',
                              onPressed: visibleEmpty ? null : _copyLogs,
                              icon: const Icon(Icons.copy_all_outlined),
                            ),
                            IconButton(
                              tooltip: 'Clear',
                              onPressed: empty ? null : _clearLogs,
                              icon: const Icon(Icons.delete_outline),
                            ),
                          ],
                        );
                      },
                    ),
                  ],
                  _ => <Widget>[],
                },
              ),
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
            key: ValueKey<int>(_navIndex),
            child: _buildMainTab(cs, textTheme, effectiveProfileId),
          ),
        ),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _navIndex,
        onDestinationSelected: (i) {
          _setHomeState(() => _navIndex = i);
          if (i == 1) _scheduleScrollLogsToEnd();
        },
        backgroundColor: cs.surface,
        surfaceTintColor: Colors.transparent,
        indicatorColor: cs.secondaryContainer,
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
    );
  }
}
