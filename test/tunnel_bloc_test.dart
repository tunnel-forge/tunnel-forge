import 'dart:async';
import 'dart:typed_data';

import 'package:bloc_test/bloc_test.dart';
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
  Future<void> disconnect() async {}

  @override
  Future<Uint8List?> getAppIcon(String packageName) async => null;

  @override
  Future<List<CandidateApp>> listVpnCandidateApps() async => const [];

  @override
  Future<void> setLogLevel(LogDisplayLevel level) async {}

  @override
  void dispose() {}
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
