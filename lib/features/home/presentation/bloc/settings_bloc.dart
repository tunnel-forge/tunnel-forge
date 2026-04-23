import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../profile_models.dart';
import '../../../home/domain/home_repositories.dart';

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

final class SettingsRoutingModeChanged extends SettingsEvent {
  const SettingsRoutingModeChanged(this.mode);

  final RoutingMode mode;

  @override
  List<Object?> get props => [mode];
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

final class SettingsAllowedAppsChanged extends SettingsEvent {
  const SettingsAllowedAppsChanged(this.apps);

  final List<String> apps;

  @override
  List<Object?> get props => [apps];
}

class SettingsState extends Equatable {
  const SettingsState({
    this.loading = true,
    this.connectionMode = ConnectionMode.vpnTunnel,
    this.routingMode = RoutingMode.fullTunnel,
    this.allowedAppPackages = const <String>[],
    this.proxySettings = const ProxySettings(),
    this.connectivityCheckSettings = const ConnectivityCheckSettings(),
    this.connectivityUrlError,
  });

  final bool loading;
  final ConnectionMode connectionMode;
  final RoutingMode routingMode;
  final List<String> allowedAppPackages;
  final ProxySettings proxySettings;
  final ConnectivityCheckSettings connectivityCheckSettings;
  final String? connectivityUrlError;

  SettingsState copyWith({
    bool? loading,
    ConnectionMode? connectionMode,
    RoutingMode? routingMode,
    List<String>? allowedAppPackages,
    ProxySettings? proxySettings,
    ConnectivityCheckSettings? connectivityCheckSettings,
    String? connectivityUrlError,
    bool clearConnectivityUrlError = false,
  }) {
    return SettingsState(
      loading: loading ?? this.loading,
      connectionMode: connectionMode ?? this.connectionMode,
      routingMode: routingMode ?? this.routingMode,
      allowedAppPackages: allowedAppPackages ?? this.allowedAppPackages,
      proxySettings: proxySettings ?? this.proxySettings,
      connectivityCheckSettings:
          connectivityCheckSettings ?? this.connectivityCheckSettings,
      connectivityUrlError: clearConnectivityUrlError
          ? null
          : (connectivityUrlError ?? this.connectivityUrlError),
    );
  }

  @override
  List<Object?> get props => [
    loading,
    connectionMode,
    routingMode,
    allowedAppPackages,
    proxySettings,
    connectivityCheckSettings,
    connectivityUrlError,
  ];
}

class SettingsBloc extends Bloc<SettingsEvent, SettingsState> {
  SettingsBloc(this._settingsRepository) : super(const SettingsState()) {
    on<SettingsStarted>(_onStarted);
    on<SettingsConnectionModeChanged>(_onConnectionModeChanged);
    on<SettingsRoutingModeChanged>(_onRoutingModeChanged);
    on<SettingsProxySettingsChanged>(_onProxySettingsChanged);
    on<SettingsConnectivityCheckSettingsChanged>(
      _onConnectivityCheckSettingsChanged,
    );
    on<SettingsAllowedAppsChanged>(_onAllowedAppsChanged);
  }

  final SettingsRepository _settingsRepository;

  Future<void> _onStarted(
    SettingsStarted event,
    Emitter<SettingsState> emit,
  ) async {
    final connectionMode = await _settingsRepository.loadConnectionMode();
    final proxySettings = await _settingsRepository.loadProxySettings();
    final connectivityCheckSettings = await _settingsRepository
        .loadConnectivityCheckSettings();
    emit(
      state.copyWith(
        loading: false,
        connectionMode: connectionMode,
        proxySettings: proxySettings,
        connectivityCheckSettings: connectivityCheckSettings,
        clearConnectivityUrlError: true,
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

  void _onRoutingModeChanged(
    SettingsRoutingModeChanged event,
    Emitter<SettingsState> emit,
  ) {
    emit(state.copyWith(routingMode: event.mode));
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

  void _onAllowedAppsChanged(
    SettingsAllowedAppsChanged event,
    Emitter<SettingsState> emit,
  ) {
    final sorted = [...event.apps]..sort();
    emit(state.copyWith(allowedAppPackages: sorted));
  }
}
