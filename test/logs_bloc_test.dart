import 'dart:typed_data';

import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tunnel_forge/core/logging/log_entry.dart';
import 'package:tunnel_forge/features/home/domain/home_models.dart';
import 'package:tunnel_forge/features/home/domain/home_repositories.dart';
import 'package:tunnel_forge/features/home/presentation/bloc/logs_bloc.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';

void main() {
  late _FakeSettingsRepository settingsRepository;
  late _RecordingTunnelRepository tunnelRepository;

  setUp(() {
    settingsRepository = _FakeSettingsRepository(
      initialLevel: LogDisplayLevel.info,
    );
    tunnelRepository = _RecordingTunnelRepository();
  });

  blocTest<LogsBloc, LogsState>(
    'startup keeps persisted display level and initializes tunnel capture to debug',
    build: () =>
        LogsBloc(_FakeLogsRepository(), settingsRepository, tunnelRepository),
    act: (bloc) => bloc.add(const LogsStarted()),
    verify: (bloc) {
      expect(tunnelRepository.levelCalls, [LogDisplayLevel.debug]);
      expect(settingsRepository.savedLevels, isEmpty);
      expect(bloc.state.level, LogDisplayLevel.info);
    },
  );

  blocTest<LogsBloc, LogsState>(
    'display level changes do not reconfigure tunnel log capture level',
    build: () =>
        LogsBloc(_FakeLogsRepository(), settingsRepository, tunnelRepository),
    seed: () => const LogsState(level: LogDisplayLevel.info),
    act: (bloc) =>
        bloc.add(const LogsLevelChangeRequested(LogDisplayLevel.error)),
    expect: () => const [LogsState(level: LogDisplayLevel.error)],
    verify: (bloc) {
      expect(settingsRepository.savedLevels, [LogDisplayLevel.error]);
      expect(tunnelRepository.levelCalls, isEmpty);
    },
  );
}

class _FakeLogsRepository implements LogsRepository {
  @override
  List<LogEntry> get entries => const <LogEntry>[];

  @override
  Stream<List<LogEntry>> get entriesStream => const Stream.empty();

  @override
  void append(LogEntry entry) {}

  @override
  void clear() {}
}

class _FakeSettingsRepository implements SettingsRepository {
  _FakeSettingsRepository({required this.initialLevel});

  final LogDisplayLevel initialLevel;
  final List<LogDisplayLevel> savedLevels = <LogDisplayLevel>[];

  @override
  Future<LogDisplayLevel> loadLogDisplayLevel() async => initialLevel;

  @override
  Future<void> saveLogDisplayLevel(LogDisplayLevel level) async {
    savedLevels.add(level);
  }

  @override
  Future<ConnectionMode> loadConnectionMode() async => ConnectionMode.vpnTunnel;

  @override
  Future<ConnectivityCheckSettings> loadConnectivityCheckSettings() async =>
      const ConnectivityCheckSettings();

  @override
  Future<ProxySettings> loadProxySettings() async => const ProxySettings();

  @override
  Future<SplitTunnelSettings> loadSplitTunnelSettings() async =>
      const SplitTunnelSettings();

  @override
  Future<void> saveConnectionMode(ConnectionMode mode) async {}

  @override
  Future<void> saveConnectivityCheckSettings(
    ConnectivityCheckSettings settings,
  ) async {}

  @override
  Future<void> saveProxySettings(ProxySettings settings) async {}

  @override
  Future<void> saveSplitTunnelSettings(SplitTunnelSettings settings) async {}
}

class _RecordingTunnelRepository implements TunnelRepository {
  final List<LogDisplayLevel> levelCalls = <LogDisplayLevel>[];

  @override
  Future<void> setLogLevel(LogDisplayLevel level) async {
    levelCalls.add(level);
  }

  @override
  Stream<TunnelHostUpdate> get tunnelStates => const Stream.empty();

  @override
  Stream<EngineLogMessage> get engineLogs => const Stream.empty();

  @override
  Stream<ProxyExposure> get proxyExposures => const Stream.empty();

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
  Future<List<CandidateApp>> listVpnCandidateApps() async => const [];

  @override
  Future<Uint8List?> getAppIcon(String packageName) async => null;

  @override
  void dispose() {}
}
