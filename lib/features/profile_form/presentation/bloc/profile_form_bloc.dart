import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../profile_models.dart';
import '../../../home/domain/home_repositories.dart';

sealed class ProfileFormEvent extends Equatable {
  const ProfileFormEvent();

  @override
  List<Object?> get props => const [];
}

final class ProfileFormStarted extends ProfileFormEvent {
  const ProfileFormStarted(this.profileId);

  final String? profileId;

  @override
  List<Object?> get props => [profileId];
}

final class ProfileFormDisplayNameChanged extends ProfileFormEvent {
  const ProfileFormDisplayNameChanged(this.value);

  final String value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormServerChanged extends ProfileFormEvent {
  const ProfileFormServerChanged(this.value);

  final String value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormUserChanged extends ProfileFormEvent {
  const ProfileFormUserChanged(this.value);

  final String value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormPasswordChanged extends ProfileFormEvent {
  const ProfileFormPasswordChanged(this.value);

  final String value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormPskChanged extends ProfileFormEvent {
  const ProfileFormPskChanged(this.value);

  final String value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormDnsAutomaticChanged extends ProfileFormEvent {
  const ProfileFormDnsAutomaticChanged(this.value);

  final bool value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormDns1Changed extends ProfileFormEvent {
  const ProfileFormDns1Changed(this.value);

  final String value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormDns1ProtocolChanged extends ProfileFormEvent {
  const ProfileFormDns1ProtocolChanged(this.value);

  final DnsProtocol value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormDns2Changed extends ProfileFormEvent {
  const ProfileFormDns2Changed(this.value);

  final String value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormDns2ProtocolChanged extends ProfileFormEvent {
  const ProfileFormDns2ProtocolChanged(this.value);

  final DnsProtocol value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormMtuChanged extends ProfileFormEvent {
  const ProfileFormMtuChanged(this.value);

  final String value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormSaveRequested extends ProfileFormEvent {
  const ProfileFormSaveRequested();
}

class ProfileFormState extends Equatable {
  const ProfileFormState({
    this.profileId = '',
    this.loading = true,
    this.saving = false,
    this.loadError,
    this.displayName = '',
    this.server = '',
    this.user = '',
    this.password = '',
    this.psk = '',
    this.dnsAutomatic = true,
    this.dns1 = '',
    this.dns1Protocol = DnsProtocol.dnsOverUdp,
    this.dns2 = '',
    this.dns2Protocol = DnsProtocol.dnsOverUdp,
    this.mtu = '${Profile.defaultVpnMtu}',
    this.messageId = 0,
    this.message,
    this.saved = false,
  });

  final String profileId;
  final bool loading;
  final bool saving;
  final String? loadError;
  final String displayName;
  final String server;
  final String user;
  final String password;
  final String psk;
  final bool dnsAutomatic;
  final String dns1;
  final DnsProtocol dns1Protocol;
  final String dns2;
  final DnsProtocol dns2Protocol;
  final String mtu;
  final int messageId;
  final String? message;
  final bool saved;

  String? get dns1ErrorText =>
      dnsAutomatic ? null : _dnsErrorText('DNS 1', dns1, dns1Protocol);
  String? get dns2ErrorText =>
      dnsAutomatic ? null : _dnsErrorText('DNS 2', dns2, dns2Protocol);
  String? get mtuErrorText => _mtuValidationMessage(mtu);

  ProfileFormState copyWith({
    String? profileId,
    bool? loading,
    bool? saving,
    String? loadError,
    bool clearLoadError = false,
    String? displayName,
    String? server,
    String? user,
    String? password,
    String? psk,
    bool? dnsAutomatic,
    String? dns1,
    DnsProtocol? dns1Protocol,
    String? dns2,
    DnsProtocol? dns2Protocol,
    String? mtu,
    int? messageId,
    String? message,
    bool clearMessage = false,
    bool? saved,
  }) {
    return ProfileFormState(
      profileId: profileId ?? this.profileId,
      loading: loading ?? this.loading,
      saving: saving ?? this.saving,
      loadError: clearLoadError ? null : (loadError ?? this.loadError),
      displayName: displayName ?? this.displayName,
      server: server ?? this.server,
      user: user ?? this.user,
      password: password ?? this.password,
      psk: psk ?? this.psk,
      dnsAutomatic: dnsAutomatic ?? this.dnsAutomatic,
      dns1: dns1 ?? this.dns1,
      dns1Protocol: dns1Protocol ?? this.dns1Protocol,
      dns2: dns2 ?? this.dns2,
      dns2Protocol: dns2Protocol ?? this.dns2Protocol,
      mtu: mtu ?? this.mtu,
      messageId: messageId ?? this.messageId,
      message: clearMessage ? null : (message ?? this.message),
      saved: saved ?? this.saved,
    );
  }

  @override
  List<Object?> get props => [
    profileId,
    loading,
    saving,
    loadError,
    displayName,
    server,
    user,
    password,
    psk,
    dnsAutomatic,
    dns1,
    dns1Protocol,
    dns2,
    dns2Protocol,
    mtu,
    messageId,
    message,
    saved,
  ];
}

String? _mtuValidationMessage(String value) {
  final mtuParsed = int.tryParse(value.trim());
  if (mtuParsed == null) {
    return 'MTU must be a number (${Profile.minVpnMtu}-${Profile.maxVpnMtu})';
  }
  if (mtuParsed < Profile.minVpnMtu || mtuParsed > Profile.maxVpnMtu) {
    return 'MTU must be between ${Profile.minVpnMtu} and ${Profile.maxVpnMtu}';
  }
  return null;
}

class ProfileFormBloc extends Bloc<ProfileFormEvent, ProfileFormState> {
  ProfileFormBloc(this._profilesRepository) : super(const ProfileFormState()) {
    on<ProfileFormStarted>(_onStarted);
    on<ProfileFormDisplayNameChanged>(
      (event, emit) =>
          emit(state.copyWith(displayName: event.value, saved: false)),
    );
    on<ProfileFormServerChanged>(
      (event, emit) => emit(state.copyWith(server: event.value, saved: false)),
    );
    on<ProfileFormUserChanged>(
      (event, emit) => emit(state.copyWith(user: event.value, saved: false)),
    );
    on<ProfileFormPasswordChanged>(
      (event, emit) =>
          emit(state.copyWith(password: event.value, saved: false)),
    );
    on<ProfileFormPskChanged>(
      (event, emit) => emit(state.copyWith(psk: event.value, saved: false)),
    );
    on<ProfileFormDnsAutomaticChanged>(
      (event, emit) =>
          emit(state.copyWith(dnsAutomatic: event.value, saved: false)),
    );
    on<ProfileFormDns1Changed>(
      (event, emit) => emit(state.copyWith(dns1: event.value, saved: false)),
    );
    on<ProfileFormDns1ProtocolChanged>(
      (event, emit) =>
          emit(state.copyWith(dns1Protocol: event.value, saved: false)),
    );
    on<ProfileFormDns2Changed>(
      (event, emit) => emit(state.copyWith(dns2: event.value, saved: false)),
    );
    on<ProfileFormDns2ProtocolChanged>(
      (event, emit) =>
          emit(state.copyWith(dns2Protocol: event.value, saved: false)),
    );
    on<ProfileFormMtuChanged>(
      (event, emit) => emit(state.copyWith(mtu: event.value, saved: false)),
    );
    on<ProfileFormSaveRequested>(_onSaveRequested);
  }

  final ProfilesRepository _profilesRepository;

  Future<void> _onStarted(
    ProfileFormStarted event,
    Emitter<ProfileFormState> emit,
  ) async {
    final profileId = event.profileId;
    if (profileId == null) {
      emit(
        state.copyWith(
          profileId: _profilesRepository.newProfileId(),
          loading: false,
          clearLoadError: true,
          displayName: 'New profile',
          server: 'vpn.example.com',
          user: '',
          password: '',
          psk: '',
          dnsAutomatic: true,
          dns1: '',
          dns1Protocol: DnsProtocol.dnsOverUdp,
          dns2: '',
          dns2Protocol: DnsProtocol.dnsOverUdp,
          mtu: '${Profile.defaultVpnMtu}',
          saved: false,
        ),
      );
      return;
    }
    final row = await _profilesRepository.loadProfileWithSecrets(profileId);
    if (row == null) {
      emit(
        state.copyWith(
          profileId: profileId,
          loading: false,
          loadError: 'This profile no longer exists.',
        ),
      );
      return;
    }
    final profile = row.profile;
    emit(
      state.copyWith(
        profileId: profile.id,
        loading: false,
        clearLoadError: true,
        displayName: profile.displayName,
        server: profile.server,
        user: profile.user,
        password: row.password,
        psk: row.psk,
        dnsAutomatic: profile.dnsAutomatic,
        dns1: profile.dns1Host,
        dns1Protocol: profile.dns1Protocol,
        dns2: profile.dns2Host,
        dns2Protocol: profile.dns2Protocol,
        mtu: '${profile.mtu}',
      ),
    );
  }

  Future<void> _onSaveRequested(
    ProfileFormSaveRequested event,
    Emitter<ProfileFormState> emit,
  ) async {
    final serverTrim = state.server.trim();
    if (serverTrim.isEmpty) {
      emit(_messageState('Enter a server address'));
      return;
    }
    final mtuErrorText = state.mtuErrorText;
    if (mtuErrorText != null) {
      emit(_messageState(mtuErrorText));
      return;
    }
    final mtuParsed = int.parse(state.mtu.trim());
    if (!state.dnsAutomatic) {
      final invalidDns1 = Profile.invalidDnsServer(
        state.dns1,
        state.dns1Protocol,
      );
      if (invalidDns1 != null) {
        emit(
          _messageState(
            Profile.validationMessageForDnsServer(
              'DNS 1',
              state.dns1,
              state.dns1Protocol,
            ),
          ),
        );
        return;
      }
      final invalidDns2 = Profile.invalidDnsServer(
        state.dns2,
        state.dns2Protocol,
      );
      if (invalidDns2 != null) {
        emit(
          _messageState(
            Profile.validationMessageForDnsServer(
              'DNS 2',
              state.dns2,
              state.dns2Protocol,
            ),
          ),
        );
        return;
      }
      if (Profile.orderedDnsServers(
        dns1Host: state.dns1,
        dns1Protocol: state.dns1Protocol,
        dns2Host: state.dns2,
        dns2Protocol: state.dns2Protocol,
      ).isEmpty) {
        emit(
          _messageState('Enter at least one DNS server or enable Automatic'),
        );
        return;
      }
    }

    emit(state.copyWith(saving: true, clearMessage: true, saved: false));
    try {
      final profile = Profile(
        id: state.profileId,
        displayName: state.displayName.trim().isEmpty
            ? serverTrim
            : state.displayName.trim(),
        server: serverTrim,
        user: state.user,
        dnsAutomatic: state.dnsAutomatic,
        dns1Host: Profile.normalizeDnsServerForProtocol(
          state.dns1,
          state.dns1Protocol,
        ),
        dns1Protocol: state.dns1Protocol,
        dns2Host: Profile.normalizeDnsServerForProtocol(
          state.dns2,
          state.dns2Protocol,
        ),
        dns2Protocol: state.dns2Protocol,
        mtu: Profile.normalizeMtu(mtuParsed),
      );
      await _profilesRepository.upsertProfile(
        profile,
        password: state.password,
        psk: state.psk,
      );
      emit(state.copyWith(saving: false, saved: true));
    } catch (_) {
      emit(_messageState('Could not save changes', saving: false));
    }
  }

  ProfileFormState _messageState(String message, {bool? saving}) {
    return state.copyWith(
      saving: saving ?? false,
      messageId: state.messageId + 1,
      message: message,
      saved: false,
    );
  }
}

String? _dnsErrorText(String label, String value, DnsProtocol protocol) {
  final invalid = Profile.invalidDnsServer(value, protocol);
  if (invalid == null) return null;
  return Profile.validationMessageForDnsServer(label, value, protocol);
}
