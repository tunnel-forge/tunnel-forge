import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../profile_models.dart';

sealed class AppSelectorEvent extends Equatable {
  const AppSelectorEvent();

  @override
  List<Object?> get props => const [];
}

final class AppSelectorStarted extends AppSelectorEvent {
  const AppSelectorStarted();
}

final class AppSelectorQueryChanged extends AppSelectorEvent {
  const AppSelectorQueryChanged(this.query);

  final String query;

  @override
  List<Object?> get props => [query];
}

final class AppSelectorToggled extends AppSelectorEvent {
  const AppSelectorToggled({required this.packageName, required this.selected});

  final String packageName;
  final bool selected;

  @override
  List<Object?> get props => [packageName, selected];
}

final class AppSelectorSelectAllRequested extends AppSelectorEvent {
  const AppSelectorSelectAllRequested();
}

final class AppSelectorClearAllRequested extends AppSelectorEvent {
  const AppSelectorClearAllRequested();
}

class AppSelectorState extends Equatable {
  const AppSelectorState({
    this.loading = true,
    this.apps = const <CandidateApp>[],
    this.query = '',
    this.selected = const <String>{},
    this.selectAllPending = false,
  });

  final bool loading;
  final List<CandidateApp> apps;
  final String query;
  final Set<String> selected;
  final bool selectAllPending;

  List<CandidateApp> get filteredApps {
    if (query.isEmpty) return apps;
    return apps
        .where(
          (app) =>
              app.label.toLowerCase().contains(query) ||
              app.packageName.toLowerCase().contains(query),
        )
        .toList();
  }

  AppSelectorState copyWith({
    bool? loading,
    List<CandidateApp>? apps,
    String? query,
    Set<String>? selected,
    bool? selectAllPending,
  }) {
    return AppSelectorState(
      loading: loading ?? this.loading,
      apps: apps ?? this.apps,
      query: query ?? this.query,
      selected: selected ?? this.selected,
      selectAllPending: selectAllPending ?? this.selectAllPending,
    );
  }

  @override
  List<Object?> get props => [loading, apps, query, selected, selectAllPending];
}

class AppSelectorBloc extends Bloc<AppSelectorEvent, AppSelectorState> {
  AppSelectorBloc({
    required Future<List<CandidateApp>> Function() loadApps,
    required Set<String> initialSelection,
  }) : _loadApps = loadApps,
       super(AppSelectorState(selected: initialSelection)) {
    on<AppSelectorStarted>(_onStarted);
    on<AppSelectorQueryChanged>(_onQueryChanged);
    on<AppSelectorToggled>(_onToggled);
    on<AppSelectorSelectAllRequested>(_onSelectAllRequested);
    on<AppSelectorClearAllRequested>(_onClearAllRequested);
  }

  final Future<List<CandidateApp>> Function() _loadApps;

  Future<void> _onStarted(
    AppSelectorStarted event,
    Emitter<AppSelectorState> emit,
  ) async {
    final apps = await _loadApps();
    emit(
      state.copyWith(
        loading: false,
        apps: apps,
        selected: state.selectAllPending
            ? apps.map((entry) => entry.packageName).toSet()
            : state.selected,
        selectAllPending: false,
      ),
    );
  }

  void _onQueryChanged(
    AppSelectorQueryChanged event,
    Emitter<AppSelectorState> emit,
  ) {
    emit(state.copyWith(query: event.query.trim().toLowerCase()));
  }

  void _onToggled(AppSelectorToggled event, Emitter<AppSelectorState> emit) {
    final next = Set<String>.from(state.selected);
    if (event.selected) {
      next.add(event.packageName);
    } else {
      next.remove(event.packageName);
    }
    emit(state.copyWith(selected: next));
  }

  void _onSelectAllRequested(
    AppSelectorSelectAllRequested event,
    Emitter<AppSelectorState> emit,
  ) {
    if (state.loading) {
      emit(state.copyWith(selectAllPending: true));
      return;
    }
    emit(
      state.copyWith(
        selected: state.apps.map((entry) => entry.packageName).toSet(),
        selectAllPending: false,
      ),
    );
  }

  void _onClearAllRequested(
    AppSelectorClearAllRequested event,
    Emitter<AppSelectorState> emit,
  ) {
    emit(state.copyWith(selected: <String>{}, selectAllPending: false));
  }
}
