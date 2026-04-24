import 'dart:typed_data';

import 'package:tunnel_forge/core/network/connectivity_checker.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_transfer.dart';
import 'package:tunnel_forge/core/logging/log_entry.dart';
import 'home_models.dart';

abstract class ProfilesRepository {
  Future<List<Profile>> loadProfiles();
  Future<String?> loadLastProfileId();
  Future<void> setLastProfileId(String? id);
  Future<ProfileSecretRow?> loadProfileWithSecrets(String id);
  Future<void> upsertProfile(
    Profile profile, {
    required String password,
    required String psk,
  });
  Future<Profile> saveImportedProfile(
    ProfileTransferEnvelope envelope, {
    bool selectAsLastProfile = true,
  });
  Future<void> deleteProfile(String id);
  Future<void> copyProfileShareLink(String id);
  Future<void> exportProfileFile(String id);
  String newProfileId();
}

abstract class SettingsRepository {
  Future<ConnectionMode> loadConnectionMode();
  Future<void> saveConnectionMode(ConnectionMode mode);
  Future<ProxySettings> loadProxySettings();
  Future<void> saveProxySettings(ProxySettings settings);
  Future<SplitTunnelSettings> loadSplitTunnelSettings();
  Future<void> saveSplitTunnelSettings(SplitTunnelSettings settings);
  Future<ConnectivityCheckSettings> loadConnectivityCheckSettings();
  Future<void> saveConnectivityCheckSettings(
    ConnectivityCheckSettings settings,
  );
  Future<LogDisplayLevel> loadLogDisplayLevel();
  Future<void> saveLogDisplayLevel(LogDisplayLevel level);
}

abstract class AppVersionRepository {
  Future<AppVersionInfo> loadInstalledVersion();
}

abstract class AppUpdateRepository {
  Future<AppReleaseInfo> fetchLatestRelease();
}

abstract class TunnelRepository {
  Stream<TunnelHostUpdate> get tunnelStates;
  Stream<EngineLogMessage> get engineLogs;
  Stream<ProxyExposure> get proxyExposures;

  Future<bool> prepareVpn();
  Future<void> connect(TunnelConnectRequest request);
  Future<void> disconnect();
  Future<void> setLogLevel(LogDisplayLevel level);
  Future<List<CandidateApp>> listVpnCandidateApps();
  Future<Uint8List?> getAppIcon(String packageName);
  void dispose();
}

abstract class ConnectivityRepository {
  Future<ConnectivityPingResult> ping(ConnectivityPingRequest request);
}

abstract class ProfileTransferRepository {
  Stream<IncomingProfileTransfer> get incomingTransfers;
  Future<List<IncomingProfileTransfer>> start();
  Future<void> dispose();
}

abstract class LogsRepository {
  List<LogEntry> get entries;
  Stream<List<LogEntry>> get entriesStream;
  void append(LogEntry entry);
  void clear();
}
