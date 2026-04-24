import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:share_plus/share_plus.dart';

import '../../../app_info_bridge.dart';
import '../../../connectivity_checker.dart';
import '../../../profile_models.dart';
import '../../../profile_store.dart';
import '../../../profile_transfer.dart';
import '../../../profile_transfer_bridge.dart';
import '../../../utils/log_entry.dart';
import '../../../vpn_client.dart';
import '../domain/home_models.dart';
import '../domain/home_repositories.dart';

class ProfilesRepositoryImpl implements ProfilesRepository {
  ProfilesRepositoryImpl(this._profileStore);

  final ProfileStore _profileStore;

  @override
  Future<void> copyProfileShareLink(String id) async {
    final row = await _profileStore.loadProfileWithSecrets(id);
    if (row == null) {
      throw const ProfileRepositoryException('This profile no longer exists.');
    }
    final envelope = ProfileTransferEnvelope.fromProfile(
      profile: row.profile,
      password: row.password,
      psk: row.psk,
    );
    await Clipboard.setData(ClipboardData(text: envelope.toTfUri()));
  }

  @override
  Future<void> deleteProfile(String id) => _profileStore.deleteProfile(id);

  @override
  Future<void> exportProfileFile(String id) async {
    final row = await _profileStore.loadProfileWithSecrets(id);
    if (row == null) {
      throw const ProfileRepositoryException('This profile no longer exists.');
    }
    final envelope = ProfileTransferEnvelope.fromProfile(
      profile: row.profile,
      password: row.password,
      psk: row.psk,
    );
    final bytes = Uint8List.fromList(utf8.encode(envelope.toFileJson()));
    final fileName = ProfileTransferEnvelope.exportFileNameFor(row.profile);
    await SharePlus.instance.share(
      ShareParams(
        files: [
          XFile.fromData(bytes, mimeType: ProfileTransferEnvelope.mimeType),
        ],
        fileNameOverrides: [fileName],
        title: 'Export TunnelForge profile',
      ),
    );
  }

  @override
  Future<String?> loadLastProfileId() => _profileStore.loadLastProfileId();

  @override
  Future<List<Profile>> loadProfiles() => _profileStore.loadProfiles();

  @override
  Future<ProfileSecretRow?> loadProfileWithSecrets(String id) async {
    final row = await _profileStore.loadProfileWithSecrets(id);
    if (row == null) return null;
    return ProfileSecretRow(
      profile: row.profile,
      password: row.password,
      psk: row.psk,
    );
  }

  @override
  String newProfileId() => ProfileStore.newProfileId();

  @override
  Future<Profile> saveImportedProfile(
    ProfileTransferEnvelope envelope, {
    bool selectAsLastProfile = true,
  }) {
    return _profileStore.saveImportedProfile(
      envelope,
      selectAsLastProfile: selectAsLastProfile,
    );
  }

  @override
  Future<void> setLastProfileId(String? id) =>
      _profileStore.setLastProfileId(id);

  @override
  Future<void> upsertProfile(
    Profile profile, {
    required String password,
    required String psk,
  }) {
    return _profileStore.upsertProfile(profile, password: password, psk: psk);
  }
}

class SettingsRepositoryImpl implements SettingsRepository {
  SettingsRepositoryImpl(this._profileStore);

  final ProfileStore _profileStore;

  @override
  Future<ConnectionMode> loadConnectionMode() {
    return _profileStore.loadConnectionMode();
  }

  @override
  Future<ConnectivityCheckSettings> loadConnectivityCheckSettings() {
    return _profileStore.loadConnectivityCheckSettings();
  }

  @override
  Future<LogDisplayLevel> loadLogDisplayLevel() {
    return _profileStore.loadLogDisplayLevel();
  }

  @override
  Future<ProxySettings> loadProxySettings() {
    return _profileStore.loadProxySettings();
  }

  @override
  Future<SplitTunnelSettings> loadSplitTunnelSettings() {
    return _profileStore.loadSplitTunnelSettings();
  }

  @override
  Future<void> saveConnectionMode(ConnectionMode mode) {
    return _profileStore.saveConnectionMode(mode);
  }

  @override
  Future<void> saveConnectivityCheckSettings(
    ConnectivityCheckSettings settings,
  ) {
    return _profileStore.saveConnectivityCheckSettings(settings);
  }

  @override
  Future<void> saveLogDisplayLevel(LogDisplayLevel level) {
    return _profileStore.saveLogDisplayLevel(level);
  }

  @override
  Future<void> saveProxySettings(ProxySettings settings) {
    return _profileStore.saveProxySettings(settings);
  }

  @override
  Future<void> saveSplitTunnelSettings(SplitTunnelSettings settings) {
    return _profileStore.saveSplitTunnelSettings(settings);
  }
}

class AppVersionRepositoryImpl implements AppVersionRepository {
  AppVersionRepositoryImpl({
    Future<PackageInfo> Function()? packageInfoLoader,
    Future<({String? versionName, String? buildNumber})> Function()?
    nativeVersionLoader,
  }) : _packageInfoLoader = packageInfoLoader ?? PackageInfo.fromPlatform,
       _nativeVersionLoader =
           nativeVersionLoader ?? AppInfoBridge().loadInstalledVersion;

  final Future<PackageInfo> Function() _packageInfoLoader;
  final Future<({String? versionName, String? buildNumber})> Function()
  _nativeVersionLoader;

  @override
  Future<AppVersionInfo> loadInstalledVersion() async {
    Object? packageInfoError;
    try {
      final info = await _packageInfoLoader();
      final displayVersion = _displayVersion(
        versionName: info.version,
        buildNumber: info.buildNumber,
      );
      if (displayVersion != null) {
        return AppVersionInfo(
          displayVersion: displayVersion,
          semanticVersion: SemanticVersion.tryParse(displayVersion),
        );
      }
      packageInfoError = const FormatException(
        'package_info_plus returned an empty version.',
      );
    } catch (error) {
      packageInfoError = error;
    }

    try {
      final native = await _nativeVersionLoader();
      final displayVersion = _displayVersion(
        versionName: native.versionName,
        buildNumber: native.buildNumber,
      );
      if (displayVersion != null) {
        return AppVersionInfo(
          displayVersion: displayVersion,
          semanticVersion: SemanticVersion.tryParse(displayVersion),
        );
      }
      return AppVersionInfo(
        semanticVersion: null,
        errorReason:
            'Installed version unavailable. Native version lookup returned empty values.',
      );
    } catch (nativeError) {
      return AppVersionInfo(
        semanticVersion: null,
        errorReason: _versionErrorReason(packageInfoError, nativeError),
      );
    }
  }

  static String? _displayVersion({
    required String? versionName,
    required String? buildNumber,
  }) {
    final version = versionName?.trim() ?? '';
    final build = buildNumber?.trim() ?? '';
    return switch ((version.isEmpty, build.isEmpty)) {
      (false, false) => '$version+$build',
      (false, true) => version,
      (true, false) => build,
      _ => null,
    };
  }

  static String _versionErrorReason(
    Object? packageInfoError,
    Object nativeError,
  ) {
    final packageInfoMessage = packageInfoError == null
        ? 'package_info_plus reason unavailable'
        : packageInfoError.toString();
    return 'Installed version unavailable. package_info_plus failed: $packageInfoMessage. Native fallback failed: ${nativeError.toString()}.';
  }
}

class AppUpdateRepositoryImpl implements AppUpdateRepository {
  AppUpdateRepositoryImpl({
    Future<String> Function(Uri uri)? fetcher,
    this.owner = 'evokelektrique',
    this.repo = 'tunnel-forge',
  }) : _fetcher = fetcher ?? _fetchJson;

  final Future<String> Function(Uri uri) _fetcher;
  final String owner;
  final String repo;

  @override
  Future<AppReleaseInfo> fetchLatestRelease() async {
    final uri = Uri.https('api.github.com', '/repos/$owner/$repo/releases');
    final body = await _fetcher(uri);
    final Object decoded;
    try {
      decoded = jsonDecode(body);
    } on FormatException catch (error) {
      throw AppUpdateException(
        kind: AppUpdateErrorKind.response,
        userMessage: 'GitHub Releases returned malformed data.',
        details: error.message,
      );
    }
    if (decoded is! List) {
      throw const AppUpdateException(
        kind: AppUpdateErrorKind.response,
        userMessage: 'GitHub Releases returned malformed data.',
        details: 'Expected a GitHub releases list.',
      );
    }

    final releases =
        decoded
            .whereType<Map>()
            .map((entry) => _parseRelease(Map<String, dynamic>.from(entry)))
            .whereType<AppReleaseInfo>()
            .toList()
          ..sort(
            (left, right) => right.publishedAt.compareTo(left.publishedAt),
          );

    if (releases.isEmpty) {
      throw const AppUpdateException(
        kind: AppUpdateErrorKind.response,
        userMessage: 'GitHub Releases returned no usable releases.',
        details: 'No valid published releases were found.',
      );
    }

    return releases.first;
  }

  AppReleaseInfo? _parseRelease(Map<String, dynamic> json) {
    if (json['draft'] == true) return null;

    final publishedAtValue = json['published_at'] as String?;
    final htmlUrl = json['html_url'] as String?;
    final tagName = json['tag_name'] as String?;
    if (publishedAtValue == null || htmlUrl == null || tagName == null) {
      return null;
    }

    final publishedAt = DateTime.tryParse(publishedAtValue);
    final version = SemanticVersion.tryParse(tagName);
    if (publishedAt == null || version == null) return null;

    return AppReleaseInfo(
      version: version,
      htmlUrl: htmlUrl,
      publishedAt: publishedAt,
      prerelease: json['prerelease'] == true,
    );
  }

  static Future<String> _fetchJson(Uri uri) async {
    final client = HttpClient()..connectionTimeout = const Duration(seconds: 5);
    try {
      final request = await client.getUrl(uri);
      request.headers.set(HttpHeaders.acceptHeader, 'application/json');
      request.headers.set(HttpHeaders.userAgentHeader, 'TunnelForgeApp/1.0');
      request.headers.set('X-GitHub-Api-Version', '2022-11-28');
      final response = await request.close();
      final body = await response.transform(utf8.decoder).join();
      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw AppUpdateException(
          kind: AppUpdateErrorKind.http,
          userMessage: 'GitHub Releases returned HTTP ${response.statusCode}.',
          statusCode: response.statusCode,
          details: body.isEmpty ? null : body,
        );
      }
      return body;
    } on TimeoutException {
      throw const AppUpdateException(
        kind: AppUpdateErrorKind.timeout,
        userMessage: 'GitHub Releases request timed out.',
      );
    } on HandshakeException catch (error) {
      throw AppUpdateException(
        kind: AppUpdateErrorKind.tls,
        userMessage: 'Secure connection to GitHub Releases failed.',
        details: error.message,
      );
    } on SocketException catch (error) {
      throw AppUpdateException(
        kind: AppUpdateErrorKind.network,
        userMessage: 'Network error while contacting GitHub Releases.',
        details: error.message,
      );
    } on AppUpdateException {
      rethrow;
    } on FormatException catch (error) {
      throw AppUpdateException(
        kind: AppUpdateErrorKind.response,
        userMessage: 'GitHub Releases returned malformed data.',
        details: error.message,
      );
    } catch (error) {
      throw AppUpdateException(
        kind: AppUpdateErrorKind.unknown,
        userMessage: 'Unexpected error while checking GitHub Releases.',
        details: error.toString(),
      );
    } finally {
      client.close(force: true);
    }
  }
}

class TunnelRepositoryImpl implements TunnelRepository {
  TunnelRepositoryImpl({VpnClient? client}) {
    _client =
        client ??
        VpnClient(
          onTunnelState: (state, detail, attemptId) {
            _tunnelStateController.add(
              TunnelHostUpdate(
                state: state,
                detail: detail,
                attemptId: attemptId,
              ),
            );
          },
          onEngineLog: (level, source, tag, message) {
            _engineLogController.add(
              EngineLogMessage(
                timestamp: DateTime.now(),
                level: level,
                source: source,
                tag: tag,
                message: message,
              ),
            );
          },
          onProxyExposureChanged: (exposure) {
            _proxyExposureController.add(exposure);
          },
        );
  }

  late final VpnClient _client;
  final StreamController<TunnelHostUpdate> _tunnelStateController =
      StreamController<TunnelHostUpdate>.broadcast();
  final StreamController<EngineLogMessage> _engineLogController =
      StreamController<EngineLogMessage>.broadcast();
  final StreamController<ProxyExposure> _proxyExposureController =
      StreamController<ProxyExposure>.broadcast();

  @override
  Stream<EngineLogMessage> get engineLogs => _engineLogController.stream;

  @override
  Stream<ProxyExposure> get proxyExposures => _proxyExposureController.stream;

  @override
  Stream<TunnelHostUpdate> get tunnelStates => _tunnelStateController.stream;

  @override
  Future<void> connect(TunnelConnectRequest request) {
    return _client.connect(
      attemptId: request.attemptId,
      server: request.server,
      profileName: request.profileName,
      connectionMode: request.connectionMode,
      user: request.user,
      password: request.password,
      psk: request.psk,
      dnsAutomatic: request.dnsAutomatic,
      dnsServers: request.dnsServers,
      mtu: request.mtu,
      splitTunnelSettings: request.splitTunnelSettings,
      proxySettings: request.proxySettings,
    );
  }

  @override
  Future<void> disconnect() => _client.disconnect();

  @override
  void dispose() {
    _client.dispose();
    unawaited(_tunnelStateController.close());
    unawaited(_engineLogController.close());
    unawaited(_proxyExposureController.close());
  }

  @override
  Future<Uint8List?> getAppIcon(String packageName) {
    return _client.getAppIcon(packageName);
  }

  @override
  Future<List<CandidateApp>> listVpnCandidateApps() {
    return _client.listVpnCandidateApps();
  }

  @override
  Future<bool> prepareVpn() => _client.prepareVpn();

  @override
  Future<void> setLogLevel(LogDisplayLevel level) {
    return _client.setLogLevel(level);
  }
}

class ConnectivityRepositoryImpl implements ConnectivityRepository {
  ConnectivityRepositoryImpl(this._checker);

  final ConnectivityChecker _checker;

  @override
  Future<ConnectivityPingResult> ping(ConnectivityPingRequest request) =>
      _checker.ping(request);
}

class ProfileTransferRepositoryImpl implements ProfileTransferRepository {
  ProfileTransferRepositoryImpl({ProfileTransferBridge? bridge})
    : _bridge = bridge ?? ProfileTransferBridge();

  final ProfileTransferBridge _bridge;

  @override
  Stream<IncomingProfileTransfer> get incomingTransfers =>
      _bridge.incomingTransfers;

  @override
  Future<void> dispose() => _bridge.dispose();

  @override
  Future<List<IncomingProfileTransfer>> start() => _bridge.start();
}

class LogsRepositoryImpl implements LogsRepository {
  static const int _maxLines = 10000;

  final List<LogEntry> _entries = <LogEntry>[];
  final StreamController<List<LogEntry>> _controller =
      StreamController<List<LogEntry>>.broadcast();

  @override
  List<LogEntry> get entries => List<LogEntry>.unmodifiable(_entries);

  @override
  Stream<List<LogEntry>> get entriesStream => _controller.stream;

  @override
  void append(LogEntry entry) {
    _entries.add(entry);
    if (_entries.length > _maxLines) {
      _entries.removeRange(0, _entries.length - _maxLines);
    }
    _controller.add(entries);
  }

  @override
  void clear() {
    if (_entries.isEmpty) return;
    _entries.clear();
    _controller.add(entries);
  }

  Future<void> dispose() async {
    await _controller.close();
  }
}

class ProfileRepositoryException implements Exception {
  const ProfileRepositoryException(this.message);

  final String message;
}
