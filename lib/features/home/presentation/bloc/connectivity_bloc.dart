import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import 'package:tunnel_forge/core/network/connectivity_checker.dart';
import 'package:tunnel_forge/features/home/presentation/widgets/connection_panel.dart';
import '../../../home/domain/log_redaction.dart';
import '../../../home/domain/home_repositories.dart';
import 'package:tunnel_forge/core/logging/log_entry.dart';

sealed class ConnectivityEvent extends Equatable {
  const ConnectivityEvent();

  @override
  List<Object?> get props => const [];
}

final class ConnectivityResetRequested extends ConnectivityEvent {
  const ConnectivityResetRequested();
}

final class ConnectivityRunRequested extends ConnectivityEvent {
  const ConnectivityRunRequested(this.request);

  final ConnectivityPingRequest request;

  @override
  List<Object?> get props => [request];
}

class ConnectivityState extends Equatable {
  const ConnectivityState({
    this.badgeState = ConnectivityBadgeState.idle,
    this.latencyMs,
  });

  final ConnectivityBadgeState badgeState;
  final int? latencyMs;

  String get badgeLabel {
    return switch (badgeState) {
      ConnectivityBadgeState.idle => 'Tap to check',
      ConnectivityBadgeState.checking => 'Checking...',
      ConnectivityBadgeState.success => '${latencyMs ?? 0} ms',
      ConnectivityBadgeState.failure => 'Unreachable',
    };
  }

  ConnectivityState copyWith({
    ConnectivityBadgeState? badgeState,
    int? latencyMs,
    bool clearLatency = false,
  }) {
    return ConnectivityState(
      badgeState: badgeState ?? this.badgeState,
      latencyMs: clearLatency ? null : (latencyMs ?? this.latencyMs),
    );
  }

  @override
  List<Object?> get props => [badgeState, latencyMs];
}

class ConnectivityBloc extends Bloc<ConnectivityEvent, ConnectivityState> {
  ConnectivityBloc(this._connectivityRepository, this._logsRepository)
    : super(const ConnectivityState()) {
    on<ConnectivityResetRequested>(_onResetRequested);
    on<ConnectivityRunRequested>(_onRunRequested);
  }

  final ConnectivityRepository _connectivityRepository;
  final LogsRepository _logsRepository;

  void _onResetRequested(
    ConnectivityResetRequested event,
    Emitter<ConnectivityState> emit,
  ) {
    emit(const ConnectivityState());
  }

  Future<void> _onRunRequested(
    ConnectivityRunRequested event,
    Emitter<ConnectivityState> emit,
  ) async {
    if (state.badgeState == ConnectivityBadgeState.checking) return;
    emit(state.copyWith(badgeState: ConnectivityBadgeState.checking));
    _log(
      LogLevel.debug,
      'Connectivity check: ${event.request.url}'
      '${event.request.route == ConnectivityPingRoute.direct ? ' via direct' : ' via proxy ${event.request.proxyHost}:${event.request.proxyPort}'}',
      tag: 'connectivity',
    );

    final result = await _connectivityRepository.ping(event.request);
    if (result.reachable && result.latencyMs != null) {
      emit(
        state.copyWith(
          badgeState: ConnectivityBadgeState.success,
          latencyMs: result.latencyMs,
        ),
      );
      _log(
        LogLevel.info,
        'Connectivity OK: ${result.latencyMs} ms'
        '${result.statusCode == null ? '' : ' status=${result.statusCode}'}',
        tag: 'connectivity',
      );
      return;
    }

    emit(
      state.copyWith(
        badgeState: ConnectivityBadgeState.failure,
        clearLatency: true,
      ),
    );
    _log(
      LogLevel.warning,
      'Connectivity failed'
      '${result.statusCode == null ? '' : ' status=${result.statusCode}'}'
      '${result.error == null || result.error!.isEmpty ? '' : ': ${redactLogMessage(result.error!)}'}',
      tag: 'connectivity',
    );
  }

  void _log(LogLevel level, String message, {required String tag}) {
    _logsRepository.append(
      LogEntry(
        timestamp: DateTime.now(),
        level: level,
        source: LogSource.dart,
        tag: tag,
        message: redactLogMessage(message),
      ),
    );
  }
}
