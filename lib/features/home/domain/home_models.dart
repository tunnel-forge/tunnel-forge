import 'package:equatable/equatable.dart';

import '../../../profile_models.dart';
import '../../../profile_transfer.dart';
import '../../../utils/log_entry.dart';

class HomeMessage extends Equatable {
  const HomeMessage({
    required this.id,
    required this.text,
    this.error = false,
  });

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
    required this.routingMode,
    required this.allowedAppPackages,
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
  final RoutingMode routingMode;
  final List<String> allowedAppPackages;
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
    routingMode,
    allowedAppPackages,
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
