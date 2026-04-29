import 'package:equatable/equatable.dart';

import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/features/tunnel/data/vpn_contract.dart';

class TunnelRuntimeState extends Equatable {
  const TunnelRuntimeState({
    required this.state,
    required this.detail,
    required this.connectionMode,
    this.attemptId = '',
    this.proxyExposure,
  });

  const TunnelRuntimeState.idle()
    : this(
        state: VpnTunnelState.stopped,
        detail: 'Idle',
        connectionMode: ConnectionMode.vpnTunnel,
      );

  final String state;
  final String detail;
  final ConnectionMode connectionMode;
  final String attemptId;
  final ProxyExposure? proxyExposure;

  @override
  List<Object?> get props => [
    state,
    detail,
    connectionMode,
    attemptId,
    proxyExposure,
  ];
}
