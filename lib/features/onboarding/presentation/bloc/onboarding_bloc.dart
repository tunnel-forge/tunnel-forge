import 'dart:async';

import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../domain/app_exit_controller.dart';
import '../../domain/onboarding_repository.dart';

const int kOnboardingAgreeCountdownSeconds = 20;

enum OnboardingFlowStatus { loading, presenting, accepted, exiting }

enum OnboardingMode { blockingFirstLaunch, readOnly }

enum OnboardingStep { language, intro, acknowledgement }

sealed class OnboardingEvent extends Equatable {
  const OnboardingEvent();

  @override
  List<Object?> get props => const [];
}

final class OnboardingStarted extends OnboardingEvent {
  const OnboardingStarted();
}

final class OnboardingReadOnlyOpened extends OnboardingEvent {
  const OnboardingReadOnlyOpened();
}

final class OnboardingContinuePressed extends OnboardingEvent {
  const OnboardingContinuePressed();
}

final class OnboardingBackPressed extends OnboardingEvent {
  const OnboardingBackPressed();
}

final class OnboardingCheckboxChanged extends OnboardingEvent {
  const OnboardingCheckboxChanged(this.checked);

  final bool checked;

  @override
  List<Object?> get props => [checked];
}

final class OnboardingAgreePressed extends OnboardingEvent {
  const OnboardingAgreePressed();
}

final class OnboardingCancelPressed extends OnboardingEvent {
  const OnboardingCancelPressed();
}

final class _OnboardingTimerTicked extends OnboardingEvent {
  const _OnboardingTimerTicked(this.secondsRemaining);

  final int secondsRemaining;

  @override
  List<Object?> get props => [secondsRemaining];
}

class OnboardingState extends Equatable {
  const OnboardingState({
    this.status = OnboardingFlowStatus.loading,
    this.mode = OnboardingMode.blockingFirstLaunch,
    this.step = OnboardingStep.language,
    this.checkboxChecked = false,
    this.secondsRemaining = kOnboardingAgreeCountdownSeconds,
  });

  final OnboardingFlowStatus status;
  final OnboardingMode mode;
  final OnboardingStep step;
  final bool checkboxChecked;
  final int secondsRemaining;

  bool get timerComplete => secondsRemaining <= 0;
  bool get isBlocking => mode == OnboardingMode.blockingFirstLaunch;
  bool get isReadOnly => mode == OnboardingMode.readOnly;
  bool get isPresenting => status == OnboardingFlowStatus.presenting;
  bool get canAgree =>
      isBlocking &&
      step == OnboardingStep.acknowledgement &&
      checkboxChecked &&
      timerComplete &&
      status == OnboardingFlowStatus.presenting;

  OnboardingState copyWith({
    OnboardingFlowStatus? status,
    OnboardingMode? mode,
    OnboardingStep? step,
    bool? checkboxChecked,
    int? secondsRemaining,
  }) {
    return OnboardingState(
      status: status ?? this.status,
      mode: mode ?? this.mode,
      step: step ?? this.step,
      checkboxChecked: checkboxChecked ?? this.checkboxChecked,
      secondsRemaining: secondsRemaining ?? this.secondsRemaining,
    );
  }

  @override
  List<Object?> get props => [
    status,
    mode,
    step,
    checkboxChecked,
    secondsRemaining,
  ];
}

class OnboardingBloc extends Bloc<OnboardingEvent, OnboardingState> {
  OnboardingBloc(this._repository, this._appExitController)
    : super(const OnboardingState()) {
    on<OnboardingStarted>(_onStarted);
    on<OnboardingReadOnlyOpened>(_onReadOnlyOpened);
    on<OnboardingContinuePressed>(_onContinuePressed);
    on<OnboardingBackPressed>(_onBackPressed);
    on<OnboardingCheckboxChanged>(_onCheckboxChanged);
    on<OnboardingAgreePressed>(_onAgreePressed);
    on<OnboardingCancelPressed>(_onCancelPressed);
    on<_OnboardingTimerTicked>(_onTimerTicked);
  }

  final OnboardingRepository _repository;
  final AppExitController _appExitController;
  StreamSubscription<int>? _countdownSubscription;

  Future<void> _onStarted(
    OnboardingStarted event,
    Emitter<OnboardingState> emit,
  ) async {
    final version = await _repository.loadAcknowledgedVersion();
    if (version == kCurrentL2tpDisclosureVersion) {
      emit(state.copyWith(status: OnboardingFlowStatus.accepted));
      return;
    }
    emit(
      state.copyWith(
        status: OnboardingFlowStatus.presenting,
        mode: OnboardingMode.blockingFirstLaunch,
        step: OnboardingStep.language,
        checkboxChecked: false,
        secondsRemaining: kOnboardingAgreeCountdownSeconds,
      ),
    );
  }

  Future<void> _onReadOnlyOpened(
    OnboardingReadOnlyOpened event,
    Emitter<OnboardingState> emit,
  ) async {
    await _stopCountdown();
    emit(
      state.copyWith(
        status: OnboardingFlowStatus.presenting,
        mode: OnboardingMode.readOnly,
        step: OnboardingStep.acknowledgement,
        checkboxChecked: false,
        secondsRemaining: 0,
      ),
    );
  }

  Future<void> _onContinuePressed(
    OnboardingContinuePressed event,
    Emitter<OnboardingState> emit,
  ) async {
    if (!state.isBlocking) return;
    if (state.step == OnboardingStep.language) {
      emit(state.copyWith(step: OnboardingStep.intro));
      return;
    }
    if (state.step != OnboardingStep.intro) return;
    emit(
      state.copyWith(
        step: OnboardingStep.acknowledgement,
        checkboxChecked: false,
        secondsRemaining: kOnboardingAgreeCountdownSeconds,
      ),
    );
    _startCountdown();
  }

  Future<void> _onBackPressed(
    OnboardingBackPressed event,
    Emitter<OnboardingState> emit,
  ) async {
    if (!state.isBlocking) {
      return;
    }
    if (state.step == OnboardingStep.acknowledgement) {
      await _stopCountdown();
      emit(
        state.copyWith(
          step: OnboardingStep.intro,
          checkboxChecked: false,
          secondsRemaining: kOnboardingAgreeCountdownSeconds,
        ),
      );
    } else if (state.step == OnboardingStep.intro) {
      emit(state.copyWith(step: OnboardingStep.language));
    }
  }

  void _onCheckboxChanged(
    OnboardingCheckboxChanged event,
    Emitter<OnboardingState> emit,
  ) {
    if (!state.isBlocking || state.step != OnboardingStep.acknowledgement) {
      return;
    }
    emit(state.copyWith(checkboxChecked: event.checked));
  }

  Future<void> _onAgreePressed(
    OnboardingAgreePressed event,
    Emitter<OnboardingState> emit,
  ) async {
    if (!state.canAgree) return;
    await _repository.saveAcknowledgedVersion(kCurrentL2tpDisclosureVersion);
    await _stopCountdown();
    emit(state.copyWith(status: OnboardingFlowStatus.accepted));
  }

  Future<void> _onCancelPressed(
    OnboardingCancelPressed event,
    Emitter<OnboardingState> emit,
  ) async {
    if (!state.isBlocking) return;
    await _stopCountdown();
    emit(state.copyWith(status: OnboardingFlowStatus.exiting));
    await _appExitController.closeApp();
  }

  void _onTimerTicked(
    _OnboardingTimerTicked event,
    Emitter<OnboardingState> emit,
  ) {
    if (!state.isBlocking || state.step != OnboardingStep.acknowledgement) {
      return;
    }
    emit(state.copyWith(secondsRemaining: event.secondsRemaining));
  }

  void _startCountdown() {
    _countdownSubscription?.cancel();
    _countdownSubscription =
        Stream<int>.periodic(
          const Duration(seconds: 1),
          (tick) => kOnboardingAgreeCountdownSeconds - tick - 1,
        ).take(kOnboardingAgreeCountdownSeconds).listen((secondsRemaining) {
          add(_OnboardingTimerTicked(secondsRemaining));
        });
  }

  Future<void> _stopCountdown() async {
    await _countdownSubscription?.cancel();
    _countdownSubscription = null;
  }

  @override
  Future<void> close() async {
    await _stopCountdown();
    return super.close();
  }
}
