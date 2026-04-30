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
import 'package:tunnel_forge/features/tunnel/domain/tunnel_runtime_state.dart';

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
    'startup bootstraps connected vpn runtime state',
    build: () => TunnelBloc(
      _FakeTunnelRepository(
        runtimeState: const TunnelRuntimeState(
          state: VpnTunnelState.connected,
          detail: 'VPN connected',
          connectionMode: ConnectionMode.vpnTunnel,
          attemptId: 'attempt-vpn',
        ),
      ),
      _FakeLogsRepository(),
    ),
    act: (bloc) => bloc.add(const TunnelStarted()),
    expect: () => const [
      TunnelState(
        tunnelUp: true,
        connectionMode: ConnectionMode.vpnTunnel,
        activeAttemptId: 'attempt-vpn',
      ),
    ],
  );

  blocTest<TunnelBloc, TunnelState>(
    'startup bootstraps connected proxy runtime state with exposure',
    build: () => TunnelBloc(
      _FakeTunnelRepository(
        runtimeState: const TunnelRuntimeState(
          state: VpnTunnelState.connected,
          detail: 'Local proxy listeners are active.',
          connectionMode: ConnectionMode.proxyOnly,
          attemptId: 'attempt-proxy',
          proxyExposure: ProxyExposure(
            active: true,
            bindAddress: '0.0.0.0',
            displayAddress: '192.168.1.20',
            httpPort: 18080,
            socksPort: 11080,
            lanRequested: true,
            lanActive: true,
          ),
        ),
      ),
      _FakeLogsRepository(),
    ),
    act: (bloc) => bloc.add(const TunnelStarted()),
    expect: () => const [
      TunnelState(
        tunnelUp: true,
        connectionMode: ConnectionMode.proxyOnly,
        activeAttemptId: 'attempt-proxy',
        proxyExposure: ProxyExposure(
          active: true,
          bindAddress: '0.0.0.0',
          displayAddress: '192.168.1.20',
          httpPort: 18080,
          socksPort: 11080,
          lanRequested: true,
          lanActive: true,
        ),
      ),
    ],
  );

  blocTest<TunnelBloc, TunnelState>(
    'connect request is ignored when runtime bootstrap has tunnel up',
    build: () {
      _countingTunnelRepository = _CountingTunnelRepository(
        runtimeState: const TunnelRuntimeState(
          state: VpnTunnelState.connected,
          detail: 'Local proxy listeners are active.',
          connectionMode: ConnectionMode.proxyOnly,
          attemptId: 'attempt-proxy',
        ),
      );
      return TunnelBloc(_countingTunnelRepository, _FakeLogsRepository());
    },
    act: (bloc) async {
      bloc.add(const TunnelStarted());
      await Future<void>.delayed(Duration.zero);
      bloc.add(TunnelConnectRequested(_connectRequest()));
    },
    expect: () => const [
      TunnelState(
        tunnelUp: true,
        connectionMode: ConnectionMode.proxyOnly,
        activeAttemptId: 'attempt-proxy',
      ),
    ],
    verify: (_) {
      expect(_countingTunnelRepository.connectCalls, 0);
    },
  );

  blocTest<TunnelBloc, TunnelState>(
    'connect request waits for pending runtime bootstrap before guard',
    build: () {
      _deferredTunnelRepository = _DeferredTunnelRepository();
      return TunnelBloc(_deferredTunnelRepository, _FakeLogsRepository());
    },
    act: (bloc) async {
      bloc.add(const TunnelStarted());
      await Future<void>.delayed(Duration.zero);
      bloc.add(TunnelConnectRequested(_connectRequest()));
      await Future<void>.delayed(Duration.zero);
      expect(_deferredTunnelRepository.connectCalls, 0);
      _deferredTunnelRepository.completeRuntimeState(
        const TunnelRuntimeState(
          state: VpnTunnelState.connected,
          detail: 'Local proxy listeners are active.',
          connectionMode: ConnectionMode.proxyOnly,
          attemptId: 'attempt-proxy',
        ),
      );
    },
    expect: () => const [
      TunnelState(
        tunnelUp: true,
        connectionMode: ConnectionMode.proxyOnly,
        activeAttemptId: 'attempt-proxy',
      ),
    ],
    verify: (_) {
      expect(_deferredTunnelRepository.connectCalls, 0);
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

  blocTest<TunnelBloc, TunnelState>(
    'disconnect timeout clears stop-pending state when runtime is idle',
    build: () => TunnelBloc(
      _MutableRuntimeTunnelRepository(const TunnelRuntimeState.idle()),
      _FakeLogsRepository(),
    ),
    seed: () => const TunnelState(
      tunnelUp: true,
      stopRequested: true,
      activeAttemptId: 'attempt-current',
    ),
    act: (bloc) {
      bloc.add(const TunnelDisconnectTimedOut('attempt-current'));
    },
    expect: () => const [TunnelState()],
  );

  blocTest<TunnelBloc, TunnelState>(
    'disconnect timeout keeps waiting when runtime is still active',
    build: () => TunnelBloc(
      _MutableRuntimeTunnelRepository(
        const TunnelRuntimeState(
          state: VpnTunnelState.connected,
          detail: 'Still active',
          connectionMode: ConnectionMode.proxyOnly,
          attemptId: 'attempt-current',
        ),
      ),
      _FakeLogsRepository(),
    ),
    seed: () => const TunnelState(
      tunnelUp: true,
      stopRequested: true,
      connectionMode: ConnectionMode.proxyOnly,
      activeAttemptId: 'attempt-current',
    ),
    act: (bloc) {
      bloc.add(const TunnelDisconnectTimedOut('attempt-current'));
    },
    expect: () => const <TunnelState>[],
  );
}

late _CountingTunnelRepository _countingTunnelRepository;
late _DeferredTunnelRepository _deferredTunnelRepository;

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
  _FakeTunnelRepository({this.runtimeState = const TunnelRuntimeState.idle()});

  final TunnelRuntimeState runtimeState;

  @override
  Stream<EngineLogMessage> get engineLogs => const Stream.empty();

  @override
  Stream<ProxyExposure> get proxyExposures => const Stream.empty();

  @override
  Stream<TunnelHostUpdate> get tunnelStates => const Stream.empty();

  @override
  Future<bool> prepareVpn() async => true;

  @override
  Future<TunnelRuntimeState> getRuntimeState() async => runtimeState;

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
  _CountingTunnelRepository({super.runtimeState});

  int connectCalls = 0;

  @override
  Future<void> connect(TunnelConnectRequest request) async {
    connectCalls += 1;
  }
}

class _MutableRuntimeTunnelRepository extends _FakeTunnelRepository {
  _MutableRuntimeTunnelRepository(this.currentRuntimeState);

  TunnelRuntimeState currentRuntimeState;

  @override
  Future<TunnelRuntimeState> getRuntimeState() async => currentRuntimeState;
}

class _DeferredTunnelRepository extends _CountingTunnelRepository {
  final Completer<TunnelRuntimeState> _runtimeState =
      Completer<TunnelRuntimeState>();

  @override
  Future<TunnelRuntimeState> getRuntimeState() => _runtimeState.future;

  void completeRuntimeState(TunnelRuntimeState state) {
    _runtimeState.complete(state);
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
