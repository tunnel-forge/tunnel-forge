import 'dart:async';

import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../utils/log_entry.dart';
import '../../../home/domain/home_repositories.dart';

sealed class LogsEvent extends Equatable {
  const LogsEvent();

  @override
  List<Object?> get props => const [];
}

final class LogsStarted extends LogsEvent {
  const LogsStarted();
}

final class LogsEntriesChanged extends LogsEvent {
  const LogsEntriesChanged(this.entries);

  final List<LogEntry> entries;

  @override
  List<Object?> get props => [entries];
}

final class LogsLevelChangeRequested extends LogsEvent {
  const LogsLevelChangeRequested(this.level);

  final LogDisplayLevel level;

  @override
  List<Object?> get props => [level];
}

final class LogsWordWrapToggled extends LogsEvent {
  const LogsWordWrapToggled();
}

final class LogsCleared extends LogsEvent {
  const LogsCleared();
}

class LogsState extends Equatable {
  const LogsState({
    this.entries = const <LogEntry>[],
    this.level = LogDisplayLevel.error,
    this.wordWrap = true,
  });

  final List<LogEntry> entries;
  final LogDisplayLevel level;
  final bool wordWrap;

  List<LogEntry> get visibleLogs {
    return entries.where((entry) => level.includes(entry.level)).toList();
  }

  String get levelLabel => level.label;

  LogsState copyWith({
    List<LogEntry>? entries,
    LogDisplayLevel? level,
    bool? wordWrap,
  }) {
    return LogsState(
      entries: entries ?? this.entries,
      level: level ?? this.level,
      wordWrap: wordWrap ?? this.wordWrap,
    );
  }

  @override
  List<Object?> get props => [entries, level, wordWrap];
}

class LogsBloc extends Bloc<LogsEvent, LogsState> {
  LogsBloc(
    this._logsRepository,
    this._settingsRepository,
    this._tunnelRepository,
  )
    : super(const LogsState()) {
    on<LogsStarted>(_onStarted);
    on<LogsEntriesChanged>(_onEntriesChanged);
    on<LogsLevelChangeRequested>(_onLevelChangeRequested);
    on<LogsWordWrapToggled>(_onWordWrapToggled);
    on<LogsCleared>(_onCleared);
  }

  final LogsRepository _logsRepository;
  final SettingsRepository _settingsRepository;
  final TunnelRepository _tunnelRepository;
  StreamSubscription<List<LogEntry>>? _entriesSub;

  Future<void> _onStarted(LogsStarted event, Emitter<LogsState> emit) async {
    final level = await _settingsRepository.loadLogDisplayLevel();
    _entriesSub?.cancel();
    _entriesSub = _logsRepository.entriesStream.listen((entries) {
      add(LogsEntriesChanged(entries));
    });
    await _tunnelRepository.setLogLevel(level);
    emit(state.copyWith(level: level, entries: _logsRepository.entries));
  }

  void _onEntriesChanged(LogsEntriesChanged event, Emitter<LogsState> emit) {
    emit(state.copyWith(entries: event.entries));
  }

  Future<void> _onLevelChangeRequested(
    LogsLevelChangeRequested event,
    Emitter<LogsState> emit,
  ) async {
    if (state.level == event.level) return;
    emit(state.copyWith(level: event.level));
    await _settingsRepository.saveLogDisplayLevel(event.level);
    await _tunnelRepository.setLogLevel(event.level);
  }

  void _onWordWrapToggled(LogsWordWrapToggled event, Emitter<LogsState> emit) {
    emit(state.copyWith(wordWrap: !state.wordWrap));
  }

  void _onCleared(LogsCleared event, Emitter<LogsState> emit) {
    _logsRepository.clear();
  }

  @override
  Future<void> close() async {
    await _entriesSub?.cancel();
    return super.close();
  }
}
