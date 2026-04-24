import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import '../../domain/home_models.dart';
import '../../../home/domain/home_repositories.dart';
import 'package:tunnel_forge/core/logging/log_entry.dart';

sealed class SettingsEvent extends Equatable {
  const SettingsEvent();

  @override
  List<Object?> get props => const [];
}

final class SettingsStarted extends SettingsEvent {
  const SettingsStarted();
}

final class SettingsConnectionModeChanged extends SettingsEvent {
  const SettingsConnectionModeChanged(this.mode);

  final ConnectionMode mode;

  @override
  List<Object?> get props => [mode];
}

final class SettingsSplitTunnelSettingsChanged extends SettingsEvent {
  const SettingsSplitTunnelSettingsChanged(this.settings);

  final SplitTunnelSettings settings;

  @override
  List<Object?> get props => [settings];
}

final class SettingsVersionCheckRequested extends SettingsEvent {
  const SettingsVersionCheckRequested();
}

final class SettingsProxySettingsChanged extends SettingsEvent {
  const SettingsProxySettingsChanged(this.settings);

  final ProxySettings settings;

  @override
  List<Object?> get props => [settings];
}

final class SettingsConnectivityCheckSettingsChanged extends SettingsEvent {
  const SettingsConnectivityCheckSettingsChanged(this.settings);

  final ConnectivityCheckSettings settings;

  @override
  List<Object?> get props => [settings];
}

class SettingsState extends Equatable {
  const SettingsState({
    this.loading = true,
    this.installedVersionLoaded = false,
    this.connectionMode = ConnectionMode.vpnTunnel,
    this.splitTunnelSettings = const SplitTunnelSettings(),
    this.proxySettings = const ProxySettings(),
    this.connectivityCheckSettings = const ConnectivityCheckSettings(),
    this.connectivityUrlError,
    this.installedVersion,
    this.installedVersionError,
    this.installedSemanticVersion,
    this.appUpdateStatus = AppUpdateStatus.idle,
    this.latestReleaseVersion,
    this.latestReleaseUrl,
    this.updateErrorMessage,
  });

  final bool loading;
  final bool installedVersionLoaded;
  final ConnectionMode connectionMode;
  final SplitTunnelSettings splitTunnelSettings;
  final ProxySettings proxySettings;
  final ConnectivityCheckSettings connectivityCheckSettings;
  final String? connectivityUrlError;
  final String? installedVersion;
  final String? installedVersionError;
  final SemanticVersion? installedSemanticVersion;
  final AppUpdateStatus appUpdateStatus;
  final String? latestReleaseVersion;
  final String? latestReleaseUrl;
  final String? updateErrorMessage;

  SettingsState copyWith({
    bool? loading,
    bool? installedVersionLoaded,
    ConnectionMode? connectionMode,
    SplitTunnelSettings? splitTunnelSettings,
    ProxySettings? proxySettings,
    ConnectivityCheckSettings? connectivityCheckSettings,
    String? connectivityUrlError,
    bool clearConnectivityUrlError = false,
    String? installedVersion,
    String? installedVersionError,
    bool clearInstalledVersionError = false,
    SemanticVersion? installedSemanticVersion,
    bool preserveInstalledSemanticVersion = true,
    AppUpdateStatus? appUpdateStatus,
    String? latestReleaseVersion,
    String? latestReleaseUrl,
    String? updateErrorMessage,
    bool clearUpdateErrorMessage = false,
  }) {
    return SettingsState(
      loading: loading ?? this.loading,
      installedVersionLoaded:
          installedVersionLoaded ?? this.installedVersionLoaded,
      connectionMode: connectionMode ?? this.connectionMode,
      splitTunnelSettings: splitTunnelSettings ?? this.splitTunnelSettings,
      proxySettings: proxySettings ?? this.proxySettings,
      connectivityCheckSettings:
          connectivityCheckSettings ?? this.connectivityCheckSettings,
      connectivityUrlError: clearConnectivityUrlError
          ? null
          : (connectivityUrlError ?? this.connectivityUrlError),
      installedVersion: installedVersion ?? this.installedVersion,
      installedVersionError: clearInstalledVersionError
          ? null
          : (installedVersionError ?? this.installedVersionError),
      installedSemanticVersion: preserveInstalledSemanticVersion
          ? (installedSemanticVersion ?? this.installedSemanticVersion)
          : installedSemanticVersion,
      appUpdateStatus: appUpdateStatus ?? this.appUpdateStatus,
      latestReleaseVersion: latestReleaseVersion ?? this.latestReleaseVersion,
      latestReleaseUrl: latestReleaseUrl ?? this.latestReleaseUrl,
      updateErrorMessage: clearUpdateErrorMessage
          ? null
          : (updateErrorMessage ?? this.updateErrorMessage),
    );
  }

  @override
  List<Object?> get props => [
    loading,
    installedVersionLoaded,
    connectionMode,
    splitTunnelSettings,
    proxySettings,
    connectivityCheckSettings,
    connectivityUrlError,
    installedVersion,
    installedVersionError,
    installedSemanticVersion,
    appUpdateStatus,
    latestReleaseVersion,
    latestReleaseUrl,
    updateErrorMessage,
  ];
}

class SettingsBloc extends Bloc<SettingsEvent, SettingsState> {
  SettingsBloc(
    this._settingsRepository,
    this._appVersionRepository,
    this._appUpdateRepository,
    this._logsRepository,
  ) : super(const SettingsState()) {
    on<SettingsStarted>(_onStarted);
    on<SettingsConnectionModeChanged>(_onConnectionModeChanged);
    on<SettingsSplitTunnelSettingsChanged>(_onSplitTunnelSettingsChanged);
    on<SettingsProxySettingsChanged>(_onProxySettingsChanged);
    on<SettingsConnectivityCheckSettingsChanged>(
      _onConnectivityCheckSettingsChanged,
    );
    on<SettingsVersionCheckRequested>(_onVersionCheckRequested);
  }

  final SettingsRepository _settingsRepository;
  final AppVersionRepository _appVersionRepository;
  final AppUpdateRepository _appUpdateRepository;
  final LogsRepository _logsRepository;

  Future<void> _onStarted(
    SettingsStarted event,
    Emitter<SettingsState> emit,
  ) async {
    final connectionModeFuture = _settingsRepository.loadConnectionMode();
    final splitTunnelSettingsFuture = _settingsRepository
        .loadSplitTunnelSettings();
    final proxySettingsFuture = _settingsRepository.loadProxySettings();
    final connectivityCheckSettingsFuture = _settingsRepository
        .loadConnectivityCheckSettings();
    final installedVersionFuture = _appVersionRepository.loadInstalledVersion();

    final connectionMode = await connectionModeFuture;
    final splitTunnelSettings = await splitTunnelSettingsFuture;
    final proxySettings = await proxySettingsFuture;
    final connectivityCheckSettings = await connectivityCheckSettingsFuture;

    emit(
      state.copyWith(
        loading: false,
        connectionMode: connectionMode,
        splitTunnelSettings: splitTunnelSettings,
        proxySettings: proxySettings,
        connectivityCheckSettings: connectivityCheckSettings,
        clearConnectivityUrlError: true,
        clearUpdateErrorMessage: true,
      ),
    );

    final installedVersion = await installedVersionFuture;
    if (installedVersion.errorReason != null) {
      _appendLog(LogLevel.warning, 'version: ${installedVersion.errorReason!}');
    }
    emit(
      state.copyWith(
        installedVersion: installedVersion.displayVersion,
        installedVersionError: installedVersion.errorReason,
        clearInstalledVersionError: installedVersion.errorReason == null,
        installedSemanticVersion: installedVersion.semanticVersion,
        preserveInstalledSemanticVersion: false,
        installedVersionLoaded: true,
      ),
    );
  }

  Future<void> _onConnectionModeChanged(
    SettingsConnectionModeChanged event,
    Emitter<SettingsState> emit,
  ) async {
    emit(state.copyWith(connectionMode: event.mode));
    await _settingsRepository.saveConnectionMode(event.mode);
  }

  Future<void> _onSplitTunnelSettingsChanged(
    SettingsSplitTunnelSettingsChanged event,
    Emitter<SettingsState> emit,
  ) async {
    final normalized = event.settings.copyWith();
    emit(state.copyWith(splitTunnelSettings: normalized));
    await _settingsRepository.saveSplitTunnelSettings(normalized);
  }

  Future<void> _onProxySettingsChanged(
    SettingsProxySettingsChanged event,
    Emitter<SettingsState> emit,
  ) async {
    emit(state.copyWith(proxySettings: event.settings));
    await _settingsRepository.saveProxySettings(event.settings);
  }

  Future<void> _onConnectivityCheckSettingsChanged(
    SettingsConnectivityCheckSettingsChanged event,
    Emitter<SettingsState> emit,
  ) async {
    final urlError = ConnectivityCheckSettings.validateUrl(event.settings.url);
    final timeoutError = ConnectivityCheckSettings.validateTimeoutMs(
      '${event.settings.timeoutMs}',
    );
    if (urlError != null || timeoutError != null) {
      emit(state.copyWith(connectivityUrlError: urlError ?? timeoutError));
      return;
    }
    final settings = state.connectivityCheckSettings.copyWith(
      url: event.settings.url,
      timeoutMs: event.settings.timeoutMs,
    );
    emit(
      state.copyWith(
        connectivityCheckSettings: settings,
        clearConnectivityUrlError: true,
      ),
    );
    await _settingsRepository.saveConnectivityCheckSettings(settings);
  }

  Future<void> _onVersionCheckRequested(
    SettingsVersionCheckRequested event,
    Emitter<SettingsState> emit,
  ) async {
    emit(
      state.copyWith(
        appUpdateStatus: AppUpdateStatus.loading,
        clearUpdateErrorMessage: true,
      ),
    );

    try {
      final release = await _appUpdateRepository.fetchLatestRelease();
      final installedVersion = state.installedSemanticVersion;
      final status = switch (installedVersion?.compareTo(release.version)) {
        null => AppUpdateStatus.comparisonUnavailable,
        < 0 => AppUpdateStatus.updateAvailable,
        > 0 => AppUpdateStatus.aheadOfRelease,
        _ => AppUpdateStatus.upToDate,
      };
      final updateMessage = status == AppUpdateStatus.comparisonUnavailable
          ? (state.installedVersionError ??
                'Installed version unavailable, so this build cannot be compared.')
          : null;

      emit(
        state.copyWith(
          appUpdateStatus: status,
          latestReleaseVersion: release.versionLabel,
          latestReleaseUrl: release.htmlUrl,
          updateErrorMessage: updateMessage,
          clearUpdateErrorMessage: updateMessage == null,
        ),
      );
      if (status == AppUpdateStatus.comparisonUnavailable) {
        _appendLog(
          LogLevel.warning,
          'update: fetched ${release.versionLabel} but could not compare installed build. ${updateMessage ?? ''}'
              .trim(),
        );
      }
    } on AppUpdateException catch (error) {
      _appendLog(
        LogLevel.warning,
        'update: ${error.userMessage}${error.details == null ? '' : ' ${error.details!}'}',
      );
      emit(
        state.copyWith(
          appUpdateStatus: AppUpdateStatus.error,
          updateErrorMessage: error.userMessage,
        ),
      );
    } catch (error) {
      _appendLog(LogLevel.error, 'update: unexpected failure $error');
      emit(
        state.copyWith(
          appUpdateStatus: AppUpdateStatus.error,
          updateErrorMessage:
              'Unexpected error while checking GitHub Releases.',
        ),
      );
    }
  }

  void _appendLog(LogLevel level, String message) {
    _logsRepository.append(
      LogEntry(
        timestamp: DateTime.now(),
        level: level,
        source: LogSource.dart,
        tag: 'settings_update',
        message: message,
      ),
    );
  }
}
