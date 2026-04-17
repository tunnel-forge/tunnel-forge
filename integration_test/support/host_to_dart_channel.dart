import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:tunnel_forge/vpn_contract.dart';

/// Simulates the Android host invoking [VpnContract.onTunnelState] on the app channel.
Future<void> simulateHostTunnelState(WidgetTester tester, String state, String detail) async {
  const codec = StandardMethodCodec();
  final data = codec.encodeMethodCall(
    MethodCall(
      VpnContract.onTunnelState,
      <String, Object?>{
        VpnContract.argTunnelState: state,
        VpnContract.argTunnelDetail: detail,
      },
    ),
  );
  await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
    VpnContract.channel,
    data,
    (ByteData? reply) {},
  );
  await tester.pump();
}
