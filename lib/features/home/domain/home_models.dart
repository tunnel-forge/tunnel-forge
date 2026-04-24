import 'package:equatable/equatable.dart';

import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_transfer.dart';
import 'package:tunnel_forge/core/logging/log_entry.dart';

class HomeMessage extends Equatable {
  const HomeMessage({required this.id, required this.text, this.error = false});

  final int id;
  final String text;
  final bool error;

  @override
  List<Object?> get props => [id, text, error];
}

class ProfileSecretRow extends Equatable {
  const ProfileSecretRow({
    required this.profile,
    required this.password,
    required this.psk,
  });

  final Profile profile;
  final String password;
  final String psk;

  @override
  List<Object?> get props => [profile, password, psk];
}

class TunnelHostUpdate extends Equatable {
  const TunnelHostUpdate({
    required this.state,
    required this.detail,
    this.attemptId = '',
  });

  final String state;
  final String detail;
  final String attemptId;

  @override
  List<Object?> get props => [state, detail, attemptId];
}

class EngineLogMessage extends Equatable {
  const EngineLogMessage({
    required this.timestamp,
    required this.level,
    required this.source,
    required this.tag,
    required this.message,
  });

  final DateTime timestamp;
  final LogLevel level;
  final LogSource source;
  final String tag;
  final String message;

  LogEntry toLogEntry() {
    return LogEntry(
      timestamp: timestamp,
      level: level,
      source: source,
      tag: tag,
      message: message,
    );
  }

  @override
  List<Object?> get props => [timestamp, level, source, tag, message];
}

class TunnelConnectRequest extends Equatable {
  const TunnelConnectRequest({
    this.attemptId = '',
    required this.activeProfileId,
    required this.profileName,
    required this.server,
    required this.user,
    required this.password,
    required this.psk,
    required this.dnsAutomatic,
    required this.dnsServers,
    required this.mtu,
    required this.connectionMode,
    required this.splitTunnelSettings,
    required this.proxySettings,
  });

  final String attemptId;
  final String? activeProfileId;
  final String? profileName;
  final String server;
  final String user;
  final String password;
  final String psk;
  final bool dnsAutomatic;
  final List<DnsServerConfig> dnsServers;
  final int mtu;
  final ConnectionMode connectionMode;
  final SplitTunnelSettings splitTunnelSettings;
  final ProxySettings proxySettings;

  @override
  List<Object?> get props => [
    attemptId,
    activeProfileId,
    profileName,
    server,
    user,
    password,
    psk,
    dnsAutomatic,
    dnsServers,
    mtu,
    connectionMode,
    splitTunnelSettings,
    proxySettings,
  ];
}

class ImportTransferRequest extends Equatable {
  const ImportTransferRequest({
    required this.transfer,
    this.selectAsLastProfile = false,
  });

  final IncomingProfileTransfer transfer;
  final bool selectAsLastProfile;

  @override
  List<Object?> get props => [transfer, selectAsLastProfile];
}

enum AppUpdateStatus {
  idle,
  loading,
  upToDate,
  updateAvailable,
  aheadOfRelease,
  comparisonUnavailable,
  error,
}

class SemanticVersion extends Equatable implements Comparable<SemanticVersion> {
  const SemanticVersion({
    required this.major,
    required this.minor,
    required this.patch,
  });

  final int major;
  final int minor;
  final int patch;

  static final RegExp _pattern = RegExp(r'^v?(\d+)\.(\d+)\.(\d+)');

  static SemanticVersion? tryParse(String value) {
    final match = _pattern.firstMatch(value.trim());
    if (match == null) return null;
    return SemanticVersion(
      major: int.parse(match.group(1)!),
      minor: int.parse(match.group(2)!),
      patch: int.parse(match.group(3)!),
    );
  }

  @override
  int compareTo(SemanticVersion other) {
    final majorCompare = major.compareTo(other.major);
    if (majorCompare != 0) return majorCompare;
    final minorCompare = minor.compareTo(other.minor);
    if (minorCompare != 0) return minorCompare;
    return patch.compareTo(other.patch);
  }

  @override
  List<Object?> get props => [major, minor, patch];

  @override
  String toString() => '$major.$minor.$patch';
}

class AppVersionInfo extends Equatable {
  const AppVersionInfo({
    this.displayVersion,
    required this.semanticVersion,
    this.errorReason,
  });

  final String? displayVersion;
  final SemanticVersion? semanticVersion;
  final String? errorReason;

  bool get hasDisplayVersion =>
      displayVersion != null && displayVersion!.trim().isNotEmpty;

  @override
  List<Object?> get props => [displayVersion, semanticVersion, errorReason];
}

enum AppUpdateErrorKind { http, timeout, network, tls, response, unknown }

class AppUpdateException with EquatableMixin implements Exception {
  const AppUpdateException({
    required this.kind,
    required this.userMessage,
    this.statusCode,
    this.details,
  });

  final AppUpdateErrorKind kind;
  final String userMessage;
  final int? statusCode;
  final String? details;

  @override
  List<Object?> get props => [kind, userMessage, statusCode, details];
}

class AppReleaseInfo extends Equatable {
  const AppReleaseInfo({
    required this.version,
    required this.htmlUrl,
    required this.publishedAt,
    required this.prerelease,
  });

  final SemanticVersion version;
  final String htmlUrl;
  final DateTime publishedAt;
  final bool prerelease;

  String get versionLabel => version.toString();

  @override
  List<Object?> get props => [version, htmlUrl, publishedAt, prerelease];
}
