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

    test('sends ordered dns servers in manual mode', () async {
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
        dnsAutomatic: false,
        dnsServers: const [
          DnsServerConfig(host: '1.1.1.1', protocol: DnsProtocol.dnsOverUdp),
          DnsServerConfig(host: '8.8.8.8', protocol: DnsProtocol.dnsOverTcp),
          DnsServerConfig(host: '1.1.1.1', protocol: DnsProtocol.dnsOverUdp),
        ],
      );

      expect(capturedCall?.method, VpnContract.connect);
      final args = Map<Object?, Object?>.from(capturedCall?.arguments as Map);
      expect(args[VpnContract.argDnsAutomatic], isFalse);
      expect(args[VpnContract.argDnsServers], [
        {
          VpnContract.argDnsServerHost: '1.1.1.1',
          VpnContract.argDnsServerProtocol: DnsProtocol.dnsOverUdp.jsonValue,
        },
        {
          VpnContract.argDnsServerHost: '8.8.8.8',
          VpnContract.argDnsServerProtocol: DnsProtocol.dnsOverTcp.jsonValue,
        },
        {
          VpnContract.argDnsServerHost: '1.1.1.1',
          VpnContract.argDnsServerProtocol: DnsProtocol.dnsOverUdp.jsonValue,
        },
      ]);
    });

    test('sends automatic dns mode without explicit servers', () async {
      MethodCall? capturedCall;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(const MethodChannel(VpnContract.channel), (
            call,
          ) async {
            capturedCall = call;
            return null;
          });

      await VpnClient().connect(server: 'x.example');

      final args = Map<Object?, Object?>.from(capturedCall?.arguments as Map);
      expect(args[VpnContract.argDnsAutomatic], isTrue);
      expect(args[VpnContract.argDnsServers], isEmpty);
    });
  });
}
