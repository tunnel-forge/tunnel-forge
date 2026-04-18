import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:tunnel_forge/vpn_contract.dart';

/// Registers a mock [VpnContract.channel] handler for widget and integration tests.
///
/// [prepareResult] is returned for [VpnContract.prepareVpn] (defaults to `true`).
/// [onConnect] can throw [PlatformException] or return `null` like the real host.
void installVpnChannelMock(
  List<String> capturedMethods, {
  Object? prepareResult = true,
  Future<Object?> Function(MethodCall call)? onConnect,
}) {
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(const MethodChannel(VpnContract.channel), (
        call,
      ) async {
        capturedMethods.add(call.method);
        switch (call.method) {
          case VpnContract.prepareVpn:
            return prepareResult;
          case VpnContract.connect:
            if (onConnect != null) {
              return onConnect(call);
            }
            return null;
          case VpnContract.disconnect:
            return null;
          default:
            return null;
        }
      });
}

void uninstallVpnChannelMock() {
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(const MethodChannel(VpnContract.channel), null);
}
