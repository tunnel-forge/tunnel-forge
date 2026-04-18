import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:tunnel_forge/vpn_client.dart';
import 'package:tunnel_forge/vpn_contract.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('VpnClient.prepareVpn', () {
    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            const MethodChannel(VpnContract.channel),
            null,
          );
    });

    test('returns false when platform returns null', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(const MethodChannel(VpnContract.channel), (
            call,
          ) async {
            expect(call.method, VpnContract.prepareVpn);
            return null;
          });
      expect(await VpnClient().prepareVpn(), isFalse);
    });

    test('returns false when platform returns non-bool', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(const MethodChannel(VpnContract.channel), (
            call,
          ) async {
            return 'yes';
          });
      expect(await VpnClient().prepareVpn(), isFalse);
    });
  });

  group('VpnClient.connect', () {
    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            const MethodChannel(VpnContract.channel),
            null,
          );
    });

    test('throws PlatformException when platform reports error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(const MethodChannel(VpnContract.channel), (
            call,
          ) async {
            if (call.method == VpnContract.connect) {
              throw PlatformException(
                code: 'vpn_permission',
                message: 'not granted',
              );
            }
            return null;
          });
      expect(
        () => VpnClient().connect(server: 'x.example'),
        throwsA(
          isA<PlatformException>().having(
            (e) => e.code,
            'code',
            'vpn_permission',
          ),
        ),
      );
    });
  });
}
