import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tunnel_forge/features/home/domain/home_models.dart';
import 'package:tunnel_forge/features/home/domain/home_repositories.dart';
import 'package:tunnel_forge/features/home/presentation/bloc/settings_bloc.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/core/logging/log_entry.dart';

class _FakeSettingsRepository implements SettingsRepository {
  ConnectionMode connectionMode = ConnectionMode.proxyOnly;
  SplitTunnelSettings splitTunnelSettings = const SplitTunnelSettings();
  ProxySettings proxySettings = const ProxySettings(httpPort: 18080);
  ConnectivityCheckSettings connectivityCheckSettings =
      const ConnectivityCheckSettings(
        url: 'https://example.com/ping',
        timeoutMs: 3200,
      );

  @override
  Future<ConnectionMode> loadConnectionMode() async => connectionMode;

  @override
  Future<ConnectivityCheckSettings> loadConnectivityCheckSettings() async {
    return connectivityCheckSettings;
  }

  @override
  Future<LogDisplayLevel> loadLogDisplayLevel() async {
    return LogDisplayLevel.error;
  }

  @override
  Future<ProxySettings> loadProxySettings() async => proxySettings;

  @override
  Future<SplitTunnelSettings> loadSplitTunnelSettings() async {
    return splitTunnelSettings;
  }

  @override
  Future<void> saveConnectionMode(ConnectionMode mode) async {
    connectionMode = mode;
  }

  @override
  Future<void> saveConnectivityCheckSettings(
    ConnectivityCheckSettings settings,
  ) async {
    connectivityCheckSettings = settings;
  }

  @override
  Future<void> saveLogDisplayLevel(LogDisplayLevel level) async {}

  @override
  Future<void> saveProxySettings(ProxySettings settings) async {
    proxySettings = settings;
  }

  @override
  Future<void> saveSplitTunnelSettings(SplitTunnelSettings settings) async {
    splitTunnelSettings = settings;
  }
}

class _FakeAppVersionRepository implements AppVersionRepository {
  _FakeAppVersionRepository(this.versionInfo);

  final AppVersionInfo versionInfo;

  @override
  Future<AppVersionInfo> loadInstalledVersion() async => versionInfo;
}

class _FakeAppUpdateRepository implements AppUpdateRepository {
  _FakeAppUpdateRepository({this.release, this.error});

  final AppReleaseInfo? release;
  final Object? error;

  @override
  Future<AppReleaseInfo> fetchLatestRelease() async {
    if (error != null) throw error!;
    return release!;
  }
}

class _FakeLogsRepository implements LogsRepository {
  final List<LogEntry> appended = <LogEntry>[];

  @override
  void append(LogEntry entry) {
    appended.add(entry);
  }

  @override
  void clear() => appended.clear();

  @override
  List<LogEntry> get entries => List<LogEntry>.unmodifiable(appended);

  @override
  Stream<List<LogEntry>> get entriesStream => Stream<List<LogEntry>>.empty();
}

void main() {
  group('SettingsBloc', () {
    blocTest<SettingsBloc, SettingsState>(
      'loads persisted settings and installed version on start',
      build: () => SettingsBloc(
        _FakeSettingsRepository(),
        _FakeAppVersionRepository(
          const AppVersionInfo(
            displayVersion: '0.3.0+11',
            semanticVersion: SemanticVersion(major: 0, minor: 3, patch: 0),
          ),
        ),
        _FakeAppUpdateRepository(
          release: AppReleaseInfo(
            version: const SemanticVersion(major: 0, minor: 3, patch: 0),
            htmlUrl: 'https://github.com/evokelektrique/tunnel-forge/releases',
            publishedAt: DateTime.utc(2026, 4, 19),
            prerelease: true,
          ),
        ),
        _FakeLogsRepository(),
      ),
      act: (bloc) => bloc.add(const SettingsStarted()),
      expect: () => [
        const SettingsState(
          loading: false,
          connectionMode: ConnectionMode.proxyOnly,
          proxySettings: ProxySettings(httpPort: 18080),
          connectivityCheckSettings: ConnectivityCheckSettings(
            url: 'https://example.com/ping',
            timeoutMs: 3200,
          ),
        ),
        const SettingsState(
          loading: false,
          installedVersionLoaded: true,
          connectionMode: ConnectionMode.proxyOnly,
          proxySettings: ProxySettings(httpPort: 18080),
          connectivityCheckSettings: ConnectivityCheckSettings(
            url: 'https://example.com/ping',
            timeoutMs: 3200,
          ),
          installedVersion: '0.3.0+11',
          installedSemanticVersion: SemanticVersion(
            major: 0,
            minor: 3,
            patch: 0,
          ),
        ),
      ],
    );

    blocTest<SettingsBloc, SettingsState>(
      'marks update available when release semver is newer than installed build',
      build: () => SettingsBloc(
        _FakeSettingsRepository(),
        _FakeAppVersionRepository(
          const AppVersionInfo(
            displayVersion: '0.3.0+11',
            semanticVersion: SemanticVersion(major: 0, minor: 3, patch: 0),
          ),
        ),
        _FakeAppUpdateRepository(
          release: AppReleaseInfo(
            version: const SemanticVersion(major: 0, minor: 4, patch: 0),
            htmlUrl:
                'https://github.com/evokelektrique/tunnel-forge/releases/tag/v0.4.0',
            publishedAt: DateTime.utc(2026, 4, 23),
            prerelease: true,
          ),
        ),
        _FakeLogsRepository(),
      ),
      act: (bloc) async {
        bloc.add(const SettingsStarted());
        await Future<void>.delayed(Duration.zero);
        bloc.add(const SettingsVersionCheckRequested());
      },
      expect: () => [
        const SettingsState(
          loading: false,
          connectionMode: ConnectionMode.proxyOnly,
          proxySettings: ProxySettings(httpPort: 18080),
          connectivityCheckSettings: ConnectivityCheckSettings(
            url: 'https://example.com/ping',
            timeoutMs: 3200,
          ),
        ),
        const SettingsState(
          loading: false,
          installedVersionLoaded: true,
          connectionMode: ConnectionMode.proxyOnly,
          proxySettings: ProxySettings(httpPort: 18080),
          connectivityCheckSettings: ConnectivityCheckSettings(
            url: 'https://example.com/ping',
            timeoutMs: 3200,
          ),
          installedVersion: '0.3.0+11',
          installedSemanticVersion: SemanticVersion(
            major: 0,
            minor: 3,
            patch: 0,
          ),
        ),
        const SettingsState(
          loading: false,
          installedVersionLoaded: true,
          connectionMode: ConnectionMode.proxyOnly,
          proxySettings: ProxySettings(httpPort: 18080),
          connectivityCheckSettings: ConnectivityCheckSettings(
            url: 'https://example.com/ping',
            timeoutMs: 3200,
          ),
          installedVersion: '0.3.0+11',
          installedSemanticVersion: SemanticVersion(
            major: 0,
            minor: 3,
            patch: 0,
          ),
          appUpdateStatus: AppUpdateStatus.loading,
        ),
        const SettingsState(
          loading: false,
          installedVersionLoaded: true,
          connectionMode: ConnectionMode.proxyOnly,
          proxySettings: ProxySettings(httpPort: 18080),
          connectivityCheckSettings: ConnectivityCheckSettings(
            url: 'https://example.com/ping',
            timeoutMs: 3200,
          ),
          installedVersion: '0.3.0+11',
          installedSemanticVersion: SemanticVersion(
            major: 0,
            minor: 3,
            patch: 0,
          ),
          appUpdateStatus: AppUpdateStatus.updateAvailable,
          latestReleaseVersion: '0.4.0',
          latestReleaseUrl:
              'https://github.com/evokelektrique/tunnel-forge/releases/tag/v0.4.0',
        ),
      ],
    );

    blocTest<SettingsBloc, SettingsState>(
      'surfaces a specific error when update checks fail',
      build: () => SettingsBloc(
        _FakeSettingsRepository(),
        _FakeAppVersionRepository(
          const AppVersionInfo(
            displayVersion: '0.3.0+11',
            semanticVersion: SemanticVersion(major: 0, minor: 3, patch: 0),
          ),
        ),
        _FakeAppUpdateRepository(
          error: const AppUpdateException(
            kind: AppUpdateErrorKind.network,
            userMessage: 'Network error while contacting GitHub Releases.',
          ),
        ),
        _FakeLogsRepository(),
      ),
      act: (bloc) async {
        bloc.add(const SettingsStarted());
        await Future<void>.delayed(Duration.zero);
        bloc.add(const SettingsVersionCheckRequested());
      },
      expect: () => [
        const SettingsState(
          loading: false,
          connectionMode: ConnectionMode.proxyOnly,
          proxySettings: ProxySettings(httpPort: 18080),
          connectivityCheckSettings: ConnectivityCheckSettings(
            url: 'https://example.com/ping',
            timeoutMs: 3200,
          ),
        ),
        const SettingsState(
          loading: false,
          installedVersionLoaded: true,
          connectionMode: ConnectionMode.proxyOnly,
          proxySettings: ProxySettings(httpPort: 18080),
          connectivityCheckSettings: ConnectivityCheckSettings(
            url: 'https://example.com/ping',
            timeoutMs: 3200,
          ),
          installedVersion: '0.3.0+11',
          installedSemanticVersion: SemanticVersion(
            major: 0,
            minor: 3,
            patch: 0,
          ),
        ),
        const SettingsState(
          loading: false,
          installedVersionLoaded: true,
          connectionMode: ConnectionMode.proxyOnly,
          proxySettings: ProxySettings(httpPort: 18080),
          connectivityCheckSettings: ConnectivityCheckSettings(
            url: 'https://example.com/ping',
            timeoutMs: 3200,
          ),
          installedVersion: '0.3.0+11',
          installedSemanticVersion: SemanticVersion(
            major: 0,
            minor: 3,
            patch: 0,
          ),
          appUpdateStatus: AppUpdateStatus.loading,
        ),
        const SettingsState(
          loading: false,
          installedVersionLoaded: true,
          connectionMode: ConnectionMode.proxyOnly,
          proxySettings: ProxySettings(httpPort: 18080),
          connectivityCheckSettings: ConnectivityCheckSettings(
            url: 'https://example.com/ping',
            timeoutMs: 3200,
          ),
          installedVersion: '0.3.0+11',
          installedSemanticVersion: SemanticVersion(
            major: 0,
            minor: 3,
            patch: 0,
          ),
          appUpdateStatus: AppUpdateStatus.error,
          updateErrorMessage: 'Network error while contacting GitHub Releases.',
        ),
      ],
    );

    blocTest<SettingsBloc, SettingsState>(
      'still checks releases when installed version is unavailable',
      build: () => SettingsBloc(
        _FakeSettingsRepository(),
        _FakeAppVersionRepository(
          const AppVersionInfo(
            semanticVersion: null,
            errorReason: 'Installed version unavailable.',
          ),
        ),
        _FakeAppUpdateRepository(
          release: AppReleaseInfo(
            version: const SemanticVersion(major: 0, minor: 4, patch: 0),
            htmlUrl:
                'https://github.com/evokelektrique/tunnel-forge/releases/tag/v0.4.0',
            publishedAt: DateTime.utc(2026, 4, 23),
            prerelease: true,
          ),
        ),
        _FakeLogsRepository(),
      ),
      act: (bloc) async {
        bloc.add(const SettingsStarted());
        await Future<void>.delayed(Duration.zero);
        bloc.add(const SettingsVersionCheckRequested());
      },
      expect: () => [
        const SettingsState(
          loading: false,
          connectionMode: ConnectionMode.proxyOnly,
          proxySettings: ProxySettings(httpPort: 18080),
          connectivityCheckSettings: ConnectivityCheckSettings(
            url: 'https://example.com/ping',
            timeoutMs: 3200,
          ),
        ),
        const SettingsState(
          loading: false,
          installedVersionLoaded: true,
          connectionMode: ConnectionMode.proxyOnly,
          proxySettings: ProxySettings(httpPort: 18080),
          connectivityCheckSettings: ConnectivityCheckSettings(
            url: 'https://example.com/ping',
            timeoutMs: 3200,
          ),
          installedVersionError: 'Installed version unavailable.',
        ),
        const SettingsState(
          loading: false,
          installedVersionLoaded: true,
          connectionMode: ConnectionMode.proxyOnly,
          proxySettings: ProxySettings(httpPort: 18080),
          connectivityCheckSettings: ConnectivityCheckSettings(
            url: 'https://example.com/ping',
            timeoutMs: 3200,
          ),
          installedVersionError: 'Installed version unavailable.',
          appUpdateStatus: AppUpdateStatus.loading,
        ),
        const SettingsState(
          loading: false,
          installedVersionLoaded: true,
          connectionMode: ConnectionMode.proxyOnly,
          proxySettings: ProxySettings(httpPort: 18080),
          connectivityCheckSettings: ConnectivityCheckSettings(
            url: 'https://example.com/ping',
            timeoutMs: 3200,
          ),
          installedVersionError: 'Installed version unavailable.',
          appUpdateStatus: AppUpdateStatus.comparisonUnavailable,
          latestReleaseVersion: '0.4.0',
          latestReleaseUrl:
              'https://github.com/evokelektrique/tunnel-forge/releases/tag/v0.4.0',
          updateErrorMessage: 'Installed version unavailable.',
        ),
      ],
    );
  });
}
