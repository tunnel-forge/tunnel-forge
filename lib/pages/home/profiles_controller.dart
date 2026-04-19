part of '../home_page.dart';

/// Loads [ProfileStore], profile picker/editor sheets, and per-app allow-list navigation.
extension _VpnHomePageProfiles on _VpnHomePageState {
  void _applyProfileToControllers(Profile p, String password, String psk) {
    _server.text = p.server;
    _user.text = p.user;
    _dns.text = p.dns;
    _mtu.text = '${p.mtu}';
    _password.text = password;
    _psk.text = psk;
  }

  void _applyNewFormTemplate() {
    _server.text = '';
    _user.text = '';
    _password.text = '';
    _psk.text = '';
    _dns.text = Profile.defaultDns;
    _mtu.text = '${Profile.defaultVpnMtu}';
  }

  Future<void> _loadPersistedProfiles() async {
    if (!_profilesLoading && mounted) {
      _setHomeState(() => _profilesLoading = true);
    }
    try {
      final list = await _profileStore.loadProfiles();
      final last = await _profileStore.loadLastProfileId();
      final connectionMode = await _profileStore.loadConnectionMode();
      final proxySettings = await _profileStore.loadProxySettings();
      final connectivityCheckSettings = await _profileStore
          .loadConnectivityCheckSettings();
      final logDisplayLevel = await _profileStore.loadLogDisplayLevel();
      if (!mounted) return;
      if (last != null && list.any((e) => e.id == last)) {
        final row = await _profileStore.loadProfileWithSecrets(last);
        if (!mounted) return;
        if (row != null) {
          _setHomeState(() {
            _profiles = list;
            _activeProfileId = last;
            _profilesLoading = false;
            _connectionMode = connectionMode;
            _proxySettings = proxySettings;
            _connectivityCheckSettings = connectivityCheckSettings;
            _logsLevel = logDisplayLevel;
            _applyProfileToControllers(row.profile, row.password, row.psk);
          });
          await _client.setLogLevel(logDisplayLevel);
          return;
        }
      }
      _setHomeState(() {
        _profiles = list;
        _activeProfileId = null;
        _profilesLoading = false;
        _connectionMode = connectionMode;
        _proxySettings = proxySettings;
        _connectivityCheckSettings = connectivityCheckSettings;
        _logsLevel = logDisplayLevel;
      });
      _applyNewFormTemplate();
      await _client.setLogLevel(logDisplayLevel);
    } catch (_) {
      _setHomeStateIfMounted(() => _profilesLoading = false);
    }
  }

  Future<void> _onProfileSelected(String? id) async {
    _setHomeState(() => _activeProfileId = id);
    if (id == null) {
      _applyNewFormTemplate();
      await _profileStore.setLastProfileId(null);
      return;
    }
    final row = await _profileStore.loadProfileWithSecrets(id);
    if (!mounted) return;
    if (row != null) {
      _setHomeState(
        () => _applyProfileToControllers(row.profile, row.password, row.psk),
      );
      await _profileStore.setLastProfileId(id);
    }
  }

  Future<void> _pickAppsForVpn() async {
    final picked = await _pickAppsWithInitial(
      Set<String>.from(_allowedAppPackages),
    );
    if (picked != null && mounted) {
      _setHomeState(() => _allowedAppPackages = picked.toList()..sort());
    }
  }

  Future<void> _setConnectionMode(ConnectionMode mode) async {
    _setHomeState(() => _connectionMode = mode);
    await _profileStore.saveConnectionMode(mode);
  }

  Future<void> _setProxySettings(ProxySettings settings) async {
    _setHomeState(() => _proxySettings = settings);
    await _profileStore.saveProxySettings(settings);
  }

  Future<void> _setConnectivityCheckSettings(
    ConnectivityCheckSettings settings,
  ) async {
    _setHomeState(() => _connectivityCheckSettings = settings);
    await _profileStore.saveConnectivityCheckSettings(settings);
  }

  Future<Set<String>?> _pickAppsWithInitial(Set<String> initial) {
    return Navigator.of(context, rootNavigator: true).push<Set<String>>(
      MaterialPageRoute(
        fullscreenDialog: true,
        builder: (ctx) => AppSelectorPage(
          initialSelection: initial,
          loadApps: _client.listVpnCandidateApps,
          loadIcon: _client.getAppIcon,
        ),
      ),
    );
  }

  Future<void> _createAndSelectNewProfile() async {
    final id = ProfileStore.newProfileId();
    final profile = Profile(
      id: id,
      displayName: 'New profile',
      server: 'example.invalid',
      user: '',
      dns: Profile.defaultDns,
    );
    try {
      await _profileStore.upsertProfile(profile, password: '', psk: '');
      if (!mounted) return;
      await _loadPersistedProfiles();
      _toast('Profile created');
    } catch (_) {
      if (mounted) _toast('Couldn\'t create profile', error: true);
    }
  }

  Future<bool> _confirmDeleteProfileById(String id) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Delete profile?'),
        content: const Text(
          'This will remove the server and saved credentials from this device.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: Theme.of(ctx).colorScheme.error,
              foregroundColor: Theme.of(ctx).colorScheme.onError,
            ),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Delete'),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return false;
    try {
      await _profileStore.deleteProfile(id);
      if (!mounted) return false;
      await _loadPersistedProfiles();
      if (!mounted) return false;
      if (_activeProfileId == null && _profiles.isNotEmpty) {
        await _onProfileSelected(_profiles.first.id);
      }
      _toast('Profile removed');
      return true;
    } catch (_) {
      if (mounted) _toast('Couldn\'t delete profile', error: true);
      return false;
    }
  }

  Future<void> _openProfilePicker() async {
    if (_profilesLoading || !mounted) return;
    await ProfilePickerSheet.show(
      context,
      getProfiles: () => List<Profile>.from(_profiles),
      getSelectedId: () =>
          _activeProfileId != null &&
              _profiles.any((p) => p.id == _activeProfileId)
          ? _activeProfileId
          : null,
      loading: _profilesLoading,
      onSelect: (id) async {
        Navigator.of(context).pop();
        await _onProfileSelected(id);
      },
      onEdit: (id) async {
        Navigator.of(context).pop();
        final saved = await ProfileEditorSheet.show(
          context,
          profileId: id,
          store: _profileStore,
        );
        if (!mounted) return;
        await _loadPersistedProfiles();
        if (saved) {
          await _onProfileSelected(id);
          if (mounted) _toast('Profile saved');
        }
      },
      onDelete: _confirmDeleteProfileById,
      onCreateNew: _createAndSelectNewProfile,
    );
  }
}
