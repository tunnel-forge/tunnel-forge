import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:tunnel_forge/profile_models.dart';
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

    test('sends ordered dns servers and legacy primary dns', () async {
      MethodCall? capturedCall;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(const MethodChannel(VpnContract.channel), (
            call,
          ) async {
            capturedCall = call;
            return null;
          });

      await VpnClient().connect(
        server: 'x.example',
        dnsServers: const ['1.1.1.1', '8.8.8.8', '1.1.1.1'],
      );

      expect(capturedCall?.method, VpnContract.connect);
      final args = Map<Object?, Object?>.from(capturedCall?.arguments as Map);
      expect(args[VpnContract.argDns], '1.1.1.1');
      expect(args[VpnContract.argDnsServers], ['1.1.1.1', '8.8.8.8']);
    });

    test('falls back to default dns when caller passes no servers', () async {
      MethodCall? capturedCall;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(const MethodChannel(VpnContract.channel), (
            call,
          ) async {
            capturedCall = call;
            return null;
          });

      await VpnClient().connect(server: 'x.example', dnsServers: const []);

      final args = Map<Object?, Object?>.from(capturedCall?.arguments as Map);
      expect(args[VpnContract.argDns], Profile.defaultDns);
      expect(args[VpnContract.argDnsServers], [Profile.defaultDns]);
    });
  });
}
