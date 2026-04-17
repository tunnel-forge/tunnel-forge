part of '../home_page.dart';

/// Bottom nav: 0 VPN, 1 Logs, 2 Settings — animated app bar and tab bodies.
extension _VpnHomePageUi on _VpnHomePageState {
  Widget _buildMainTab(ColorScheme cs, TextTheme textTheme, String? effectiveProfileId) {
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
        : (_profiles.isEmpty ? 'Create your first profile' : 'Select a saved profile');

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
            connectButtonLabel: _connectButtonLabel,
            onPrimary: _primaryAction,
            colorScheme: cs,
            textTheme: textTheme,
          ),
        );
      case 1:
        return ListenableBuilder(
          listenable: _logBuffer,
          builder: (context, _) => LogsPanel(
            logs: _logBuffer.lines,
            scrollController: _logsScroll,
            colorScheme: cs,
            textTheme: textTheme,
            stickToBottom: _logsStickToBottom,
            onJumpToLatest: _jumpLogsToBottom,
            wordWrap: _logsWordWrap,
          ),
        );
      default:
        return SettingsPanel(
          themeMode: widget.themeMode,
          onThemeModeChanged: widget.onThemeModeChanged,
          routingMode: _routingMode,
          allowedAppPackages: _allowedAppPackages,
          onRoutingModeChanged: (m) => setState(() => _routingMode = m),
          onChooseApps: _pickAppsForVpn,
          routingLocked: _profilesLoading || _busy || _tunnelUp || _awaitingTunnel,
          colorScheme: cs,
          textTheme: textTheme,
        );
    }
  }

  Widget _buildHomeScaffold(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;
    final effectiveProfileId = _activeProfileId != null && _profiles.any((p) => p.id == _activeProfileId) ? _activeProfileId : null;
    final appBarTitle = switch (_navIndex) { 0 => 'Tunnel Forge', 1 => 'Logs', _ => 'Settings' };

    return Scaffold(
      appBar: AppBar(
        title: AnimatedSwitcher(
          duration: const Duration(milliseconds: 360),
          switchInCurve: Curves.easeOutCubic,
          switchOutCurve: Curves.easeInCubic,
          transitionBuilder: (child, animation) {
            final curved = CurvedAnimation(parent: animation, curve: Curves.easeOutCubic);
            return FadeTransition(
              opacity: curved,
              child: SlideTransition(
                position: Tween<Offset>(begin: const Offset(0, -0.18), end: Offset.zero).animate(curved),
                child: ScaleTransition(scale: Tween<double>(begin: 0.98, end: 1).animate(curved), child: child),
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
              final curved = CurvedAnimation(parent: animation, curve: Curves.easeOutCubic);
              return FadeTransition(
                opacity: curved,
                child: SlideTransition(position: Tween<Offset>(begin: const Offset(0.08, 0), end: Offset.zero).animate(curved), child: child),
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
                      IconButton(
                        tooltip: _logsWordWrap ? 'Turn off word wrap (wide lines scroll sideways)' : 'Turn on word wrap',
                        onPressed: () => setState(() => _logsWordWrap = !_logsWordWrap),
                        icon: Icon(_logsWordWrap ? Icons.wrap_text : Icons.swap_horiz),
                      ),
                      ListenableBuilder(
                        listenable: _logBuffer,
                        builder: (context, _) {
                          final empty = _logBuffer.isEmpty;
                          return Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              IconButton(tooltip: 'Copy all', onPressed: empty ? null : _copyLogs, icon: const Icon(Icons.copy_all_outlined)),
                              IconButton(tooltip: 'Clear', onPressed: empty ? null : _clearLogs, icon: const Icon(Icons.delete_outline)),
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
            children: <Widget>[...previousChildren, if (currentChild != null) currentChild],
          ),
          transitionBuilder: (child, animation) {
            final curved = CurvedAnimation(parent: animation, curve: Curves.easeOutCubic);
            return FadeTransition(
              opacity: curved,
              child: SlideTransition(
                position: Tween<Offset>(begin: const Offset(0.08, 0), end: Offset.zero).animate(curved),
                child: ScaleTransition(scale: Tween<double>(begin: 0.985, end: 1).animate(curved), child: child),
              ),
            );
          },
          child: SizedBox.expand(key: ValueKey<int>(_navIndex), child: _buildMainTab(cs, textTheme, effectiveProfileId)),
        ),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _navIndex,
        onDestinationSelected: (i) {
          setState(() => _navIndex = i);
          if (i == 1) _scheduleScrollLogsToEnd();
        },
        backgroundColor: cs.surface,
        surfaceTintColor: Colors.transparent,
        indicatorColor: cs.secondaryContainer,
        height: 72,
        labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
        animationDuration: const Duration(milliseconds: 360),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.vpn_key_outlined), selectedIcon: Icon(Icons.vpn_key), label: 'VPN'),
          NavigationDestination(icon: Icon(Icons.article_outlined), selectedIcon: Icon(Icons.article), label: 'Logs'),
          NavigationDestination(icon: Icon(Icons.settings_outlined), selectedIcon: Icon(Icons.settings), label: 'Settings'),
        ],
      ),
    );
  }
}
