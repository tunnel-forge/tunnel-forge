import 'dart:async';

import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import 'package:tunnel_forge/l10n/app_localizations.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_transfer.dart';
import 'package:tunnel_forge/features/profiles/data/profile_transfer_contract.dart';
import '../../../home/data/home_repositories_impl.dart';
import '../../../home/domain/home_models.dart';
import '../../../home/domain/home_repositories.dart';

sealed class ProfilesEvent extends Equatable {
  const ProfilesEvent();

  @override
  List<Object?> get props => const [];
}

final class ProfilesStarted extends ProfilesEvent {
  const ProfilesStarted();
}

final class ProfilesSelectionChanged extends ProfilesEvent {
  const ProfilesSelectionChanged(this.id);

  final String? id;

  @override
  List<Object?> get props => [id];
}

final class ProfilesImportRequested extends ProfilesEvent {
  const ProfilesImportRequested(this.request);

  final ImportTransferRequest request;

  @override
  List<Object?> get props => [request];
}

final class ProfilesDeleteRequested extends ProfilesEvent {
  const ProfilesDeleteRequested(this.id);

  final String id;

  @override
  List<Object?> get props => [id];
}

final class ProfilesRefreshRequested extends ProfilesEvent {
  const ProfilesRefreshRequested({this.preferredActiveId});

  final String? preferredActiveId;

  @override
  List<Object?> get props => [preferredActiveId];
}

final class ProfilesCopyShareLinkRequested extends ProfilesEvent {
  const ProfilesCopyShareLinkRequested(this.id);

  final String id;

  @override
  List<Object?> get props => [id];
}

final class ProfilesExportFileRequested extends ProfilesEvent {
  const ProfilesExportFileRequested(this.id);

  final String id;

  @override
  List<Object?> get props => [id];
}

final class ProfilesImportSelectionPolicyChanged extends ProfilesEvent {
  const ProfilesImportSelectionPolicyChanged(
    this.selectImportedProfileWhenIdle,
  );

  final bool selectImportedProfileWhenIdle;

  @override
  List<Object?> get props => [selectImportedProfileWhenIdle];
}

class ProfilesState extends Equatable {
  const ProfilesState({
    this.loading = true,
    this.profiles = const <Profile>[],
    this.activeProfileId,
    this.activeProfileRow,
    this.selectImportedProfileWhenIdle = true,
    this.message,
  });

  final bool loading;
  final List<Profile> profiles;
  final String? activeProfileId;
  final ProfileSecretRow? activeProfileRow;
  final bool selectImportedProfileWhenIdle;
  final HomeMessage? message;

  bool get hasActiveProfile {
    final activeId = activeProfileId;
    return activeId != null &&
        profiles.any((profile) => profile.id == activeId);
  }

  ProfilesState copyWith({
    bool? loading,
    List<Profile>? profiles,
    String? activeProfileId,
    bool clearActiveProfileId = false,
    ProfileSecretRow? activeProfileRow,
    bool clearActiveProfileRow = false,
    bool? selectImportedProfileWhenIdle,
    HomeMessage? message,
    bool clearMessage = false,
  }) {
    return ProfilesState(
      loading: loading ?? this.loading,
      profiles: profiles ?? this.profiles,
      activeProfileId: clearActiveProfileId
          ? null
          : (activeProfileId ?? this.activeProfileId),
      activeProfileRow: clearActiveProfileRow
          ? null
          : (activeProfileRow ?? this.activeProfileRow),
      selectImportedProfileWhenIdle:
          selectImportedProfileWhenIdle ?? this.selectImportedProfileWhenIdle,
      message: clearMessage ? null : (message ?? this.message),
    );
  }

  @override
  List<Object?> get props => [
    loading,
    profiles,
    activeProfileId,
    activeProfileRow,
    selectImportedProfileWhenIdle,
    message,
  ];
}

class ProfilesBloc extends Bloc<ProfilesEvent, ProfilesState> {
  ProfilesBloc(this._profilesRepository, this._transferRepository)
    : super(const ProfilesState()) {
    on<ProfilesStarted>(_onStarted);
    on<ProfilesSelectionChanged>(_onSelectionChanged);
    on<ProfilesImportRequested>(_onImportRequested);
    on<ProfilesDeleteRequested>(_onDeleteRequested);
    on<ProfilesRefreshRequested>(_onRefreshRequested);
    on<ProfilesCopyShareLinkRequested>(_onCopyShareLinkRequested);
    on<ProfilesExportFileRequested>(_onExportFileRequested);
    on<ProfilesImportSelectionPolicyChanged>(_onImportSelectionPolicyChanged);
  }

  final ProfilesRepository _profilesRepository;
  final ProfileTransferRepository _transferRepository;
  StreamSubscription<IncomingProfileTransfer>? _transferSub;
  int _messageId = 0;

  Future<void> _onStarted(
    ProfilesStarted event,
    Emitter<ProfilesState> emit,
  ) async {
    await _reloadProfiles(
      emit,
      preferredActiveId: await _profilesRepository.loadLastProfileId(),
    );
    await _transferSub?.cancel();
    _transferSub = _transferRepository.incomingTransfers.listen(
      _queueIncomingTransfer,
    );
    final pendingTransfers = await _transferRepository.start();
    for (final transfer in pendingTransfers) {
      _queueIncomingTransfer(transfer);
    }
  }

  Future<void> _onSelectionChanged(
    ProfilesSelectionChanged event,
    Emitter<ProfilesState> emit,
  ) async {
    if (event.id == null) {
      await _profilesRepository.setLastProfileId(null);
      emit(
        state.copyWith(clearActiveProfileId: true, clearActiveProfileRow: true),
      );
      return;
    }
    final row = await _profilesRepository.loadProfileWithSecrets(event.id!);
    if (row == null) {
      emit(
        state.copyWith(
          message: _nextMessage(
            AppText.pick(
              'This profile no longer exists.',
              'این پروفایل دیگر وجود ندارد.',
            ),
            error: true,
          ),
        ),
      );
      return;
    }
    await _profilesRepository.setLastProfileId(event.id);
    emit(state.copyWith(activeProfileId: event.id, activeProfileRow: row));
  }

  Future<void> _onImportRequested(
    ProfilesImportRequested event,
    Emitter<ProfilesState> emit,
  ) async {
    final transfer = event.request.transfer;
    if (transfer.isError) {
      emit(
        state.copyWith(
          message: _nextMessage(
            transfer.message ??
                AppText.pick(
                  'Couldn\'t open the incoming profile',
                  'پروفایل دریافتی باز نشد',
                ),
            error: true,
          ),
        ),
      );
      return;
    }
    try {
      final envelope = ProfileTransferEnvelope.fromIncomingTransfer(transfer);
      final imported = await _profilesRepository.saveImportedProfile(
        envelope,
        selectAsLastProfile: event.request.selectAsLastProfile,
      );
      await _reloadProfiles(
        emit,
        preferredActiveId: event.request.selectAsLastProfile
            ? imported.id
            : null,
      );
      final source = switch ((transfer.source, transfer.type)) {
        ('Clipboard', ProfileTransferContract.typeTfUri) => AppText.pick(
          'a clipboard share link',
          'یک پیوند اشتراک کلیپ‌بورد',
        ),
        ('Clipboard', _) => AppText.pick('the clipboard', 'کلیپ‌بورد'),
        (_, ProfileTransferContract.typeTfUri) => AppText.pick(
          'a share link',
          'یک پیوند اشتراک',
        ),
        _ => AppText.pick('a .tfp file', 'یک فایل .tfp'),
      };
      emit(
        state.copyWith(
          message: _nextMessage(
            AppText.pick(
              'Imported profile "${imported.displayName}" from $source',
              'پروفایل «${imported.displayName}» از $source وارد شد',
            ),
          ),
        ),
      );
    } on FormatException catch (error) {
      emit(state.copyWith(message: _nextMessage(error.message, error: true)));
    } catch (_) {
      emit(
        state.copyWith(
          message: _nextMessage(
            AppText.pick('Couldn\'t import profile', 'پروفایل وارد نشد'),
            error: true,
          ),
        ),
      );
    }
  }

  Future<void> _onDeleteRequested(
    ProfilesDeleteRequested event,
    Emitter<ProfilesState> emit,
  ) async {
    try {
      await _profilesRepository.deleteProfile(event.id);
      final shouldSelectFallback = state.activeProfileId == event.id;
      final nextId = shouldSelectFallback ? null : state.activeProfileId;
      await _reloadProfiles(emit, preferredActiveId: nextId);
      if (shouldSelectFallback &&
          state.activeProfileId == null &&
          state.profiles.isNotEmpty) {
        add(ProfilesSelectionChanged(state.profiles.first.id));
      }
      emit(
        state.copyWith(
          message: _nextMessage(
            AppText.pick('Profile removed', 'پروفایل حذف شد'),
          ),
        ),
      );
    } catch (_) {
      emit(
        state.copyWith(
          message: _nextMessage(
            AppText.pick('Couldn\'t delete profile', 'پروفایل حذف نشد'),
            error: true,
          ),
        ),
      );
    }
  }

  Future<void> _onRefreshRequested(
    ProfilesRefreshRequested event,
    Emitter<ProfilesState> emit,
  ) async {
    await _reloadProfiles(
      emit,
      preferredActiveId: event.preferredActiveId ?? state.activeProfileId,
    );
  }

  Future<void> _onCopyShareLinkRequested(
    ProfilesCopyShareLinkRequested event,
    Emitter<ProfilesState> emit,
  ) async {
    try {
      await _profilesRepository.copyProfileShareLink(event.id);
      emit(
        state.copyWith(
          message: _nextMessage(
            AppText.pick('Share link copied', 'پیوند اشتراک کپی شد'),
          ),
        ),
      );
    } on ProfileRepositoryException catch (error) {
      emit(state.copyWith(message: _nextMessage(error.message, error: true)));
    } catch (_) {
      emit(
        state.copyWith(
          message: _nextMessage(
            AppText.pick('Couldn\'t copy share link', 'پیوند اشتراک کپی نشد'),
            error: true,
          ),
        ),
      );
    }
  }

  Future<void> _onExportFileRequested(
    ProfilesExportFileRequested event,
    Emitter<ProfilesState> emit,
  ) async {
    try {
      await _profilesRepository.exportProfileFile(event.id);
      emit(
        state.copyWith(
          message: _nextMessage(
            AppText.pick(
              'Profile file ready to save or share',
              'فایل پروفایل برای ذخیره یا اشتراک آماده است',
            ),
          ),
        ),
      );
    } on ProfileRepositoryException catch (error) {
      emit(state.copyWith(message: _nextMessage(error.message, error: true)));
    } catch (_) {
      emit(
        state.copyWith(
          message: _nextMessage(
            AppText.pick('Couldn\'t export .tfp file', 'خروجی .tfp ساخته نشد'),
            error: true,
          ),
        ),
      );
    }
  }

  void _onImportSelectionPolicyChanged(
    ProfilesImportSelectionPolicyChanged event,
    Emitter<ProfilesState> emit,
  ) {
    emit(
      state.copyWith(
        selectImportedProfileWhenIdle: event.selectImportedProfileWhenIdle,
      ),
    );
  }

  void _queueIncomingTransfer(IncomingProfileTransfer transfer) {
    add(
      ProfilesImportRequested(
        ImportTransferRequest(
          transfer: transfer,
          selectAsLastProfile: state.selectImportedProfileWhenIdle,
        ),
      ),
    );
  }

  Future<void> _reloadProfiles(
    Emitter<ProfilesState> emit, {
    String? preferredActiveId,
  }) async {
    emit(state.copyWith(loading: true, clearMessage: true));
    final profiles = await _profilesRepository.loadProfiles();
    final targetId =
        preferredActiveId != null &&
            profiles.any((profile) => profile.id == preferredActiveId)
        ? preferredActiveId
        : null;
    ProfileSecretRow? targetRow;
    if (targetId != null) {
      targetRow = await _profilesRepository.loadProfileWithSecrets(targetId);
    }
    emit(
      state.copyWith(
        loading: false,
        profiles: profiles,
        activeProfileId: targetRow == null ? null : targetId,
        activeProfileRow: targetRow,
      ),
    );
  }

  HomeMessage _nextMessage(String text, {bool error = false}) {
    _messageId += 1;
    return HomeMessage(id: _messageId, text: text, error: error);
  }

  @override
  Future<void> close() async {
    await _transferSub?.cancel();
    return super.close();
  }
}
