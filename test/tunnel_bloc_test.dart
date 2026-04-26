import 'dart:async';
import 'dart:typed_data';

import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tunnel_forge/features/home/domain/home_models.dart';
import 'package:tunnel_forge/features/home/domain/home_repositories.dart';
import 'package:tunnel_forge/features/home/presentation/bloc/tunnel_bloc.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/core/logging/log_entry.dart';
import 'package:tunnel_forge/features/tunnel/data/vpn_contract.dart';

void main() {
  blocTest<TunnelBloc, TunnelState>(
    'ignores stale failure from an older attempt while a newer tunnel is up',
    build: () => TunnelBloc(_FakeTunnelRepository(), _FakeLogsRepository()),
    seed: () =>
        const TunnelState(tunnelUp: true, activeAttemptId: 'attempt-new'),
    act: (bloc) {
      bloc.add(
        const TunnelHostStateReceived(
          TunnelHostUpdate(
            state: VpnTunnelState.failed,
            detail: 'L2TP handshake failed.',
            attemptId: 'attempt-old',
          ),
        ),
      );
    },
    expect: () => const <TunnelState>[],
  );

  blocTest<TunnelBloc, TunnelState>(
    'current failure clears active attempt and disconnects the tunnel',
    build: () => TunnelBloc(_FakeTunnelRepository(), _FakeLogsRepository()),
    seed: () =>
        const TunnelState(tunnelUp: true, activeAttemptId: 'attempt-current'),
    act: (bloc) {
      bloc.add(
        const TunnelHostStateReceived(
          TunnelHostUpdate(
            state: VpnTunnelState.failed,
            detail: 'L2TP handshake failed.',
            attemptId: 'attempt-current',
          ),
        ),
      );
    },
    expect: () => [
      const TunnelState(tunnelUp: false, activeAttemptId: null),
      const TunnelState(
        tunnelUp: false,
        activeAttemptId: null,
        message: HomeMessage(
          id: 1,
          text: 'L2TP handshake failed.',
          error: true,
        ),
      ),
    ],
  );

  blocTest<TunnelBloc, TunnelState>(
    'shutdown-induced proxy failure during stop is treated as stopped',
    build: () => TunnelBloc(_FakeTunnelRepository(), _FakeLogsRepository()),
    seed: () => const TunnelState(
      tunnelUp: true,
      stopRequested: true,
      connectionMode: ConnectionMode.proxyOnly,
      activeAttemptId: 'attempt-current',
    ),
    act: (bloc) {
      bloc.add(
        const TunnelHostStateReceived(
          TunnelHostUpdate(
            state: VpnTunnelState.failed,
            detail: 'Local proxy connection was lost.',
            attemptId: 'attempt-current',
          ),
        ),
      );
    },
    expect: () => const [TunnelState(connectionMode: ConnectionMode.proxyOnly)],
  );

  blocTest<TunnelBloc, TunnelState>(
    'connect request is ignored while disconnect is pending',
    build: () {
      return TunnelBloc(_countingTunnelRepository, _FakeLogsRepository());
    },
    setUp: () {
      _countingTunnelRepository = _CountingTunnelRepository();
    },
    seed: () => const TunnelState(stopRequested: true),
    act: (bloc) {
      bloc.add(TunnelConnectRequested(_connectRequest()));
    },
    expect: () => const <TunnelState>[],
    verify: (_) {
      expect(_countingTunnelRepository.connectCalls, 0);
    },
  );

  blocTest<TunnelBloc, TunnelState>(
    'untagged terminal event is ignored while an attempt is active',
    build: () => TunnelBloc(_FakeTunnelRepository(), _FakeLogsRepository()),
    seed: () => const TunnelState(
      tunnelUp: true,
      stopRequested: true,
      connectionMode: ConnectionMode.proxyOnly,
      activeAttemptId: 'attempt-current',
    ),
    act: (bloc) {
      bloc.add(
        const TunnelHostStateReceived(
          TunnelHostUpdate(
            state: VpnTunnelState.stopped,
            detail: 'Stopped by app',
            attemptId: '',
          ),
        ),
      );
    },
    expect: () => const <TunnelState>[],
  );

  blocTest<TunnelBloc, TunnelState>(
    'await timeout stays non-terminal while Android is still connecting',
    build: () => TunnelBloc(_FakeTunnelRepository(), _FakeLogsRepository()),
    seed: () => const TunnelState(
      awaitingTunnel: true,
      activeAttemptId: 'attempt-current',
      connectionMode: ConnectionMode.proxyOnly,
    ),
    act: (bloc) {
      bloc.add(const TunnelAwaitTimedOut('attempt-current'));
    },
    expect: () => const <TunnelState>[],
    verify: (bloc) {
      expect(bloc.state.awaitingTunnel, isTrue);
      expect(bloc.state.activeAttemptId, 'attempt-current');
      expect(bloc.state.message, isNull);
      expect(bloc.state.timedOutThisAttempt, isFalse);
    },
  );
}

late _CountingTunnelRepository _countingTunnelRepository;

TunnelConnectRequest _connectRequest() {
  return const TunnelConnectRequest(
    activeProfileId: null,
    profileName: null,
    server: 'vpn.example',
    user: '',
    password: '',
    psk: '',
    dnsAutomatic: true,
    dnsServers: [],
    mtu: Profile.defaultVpnMtu,
    connectionMode: ConnectionMode.vpnTunnel,
    splitTunnelSettings: SplitTunnelSettings(),
    proxySettings: ProxySettings(),
  );
}

class _FakeTunnelRepository implements TunnelRepository {
  @override
  Stream<EngineLogMessage> get engineLogs => const Stream.empty();

  @override
  Stream<ProxyExposure> get proxyExposures => const Stream.empty();

  @override
  Stream<TunnelHostUpdate> get tunnelStates => const Stream.empty();

  @override
  Future<bool> prepareVpn() async => true;

  @override
  Future<void> connect(TunnelConnectRequest request) async {}

  @override
  Future<void> disconnect({
    required ConnectionMode connectionMode,
    String attemptId = '',
  }) async {}

  @override
  Future<Uint8List?> getAppIcon(String packageName) async => null;

  @override
  Future<List<CandidateApp>> listVpnCandidateApps() async => const [];

  @override
  Future<void> setLogLevel(LogDisplayLevel level) async {}

  @override
  void dispose() {}
}

class _CountingTunnelRepository extends _FakeTunnelRepository {
  int connectCalls = 0;

  @override
  Future<void> connect(TunnelConnectRequest request) async {
    connectCalls += 1;
  }
}

class _FakeLogsRepository implements LogsRepository {
  @override
  final List<LogEntry> entries = <LogEntry>[];

  @override
  Stream<List<LogEntry>> get entriesStream => const Stream.empty();

  @override
  void append(LogEntry entry) {
    entries.add(entry);
  }

  @override
  void clear() {
    entries.clear();
  }
}
