import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/core/logging/log_entry.dart';
import 'package:tunnel_forge/features/tunnel/data/vpn_client.dart';
import 'package:tunnel_forge/features/tunnel/data/vpn_contract.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('VpnContract', () {
    test('channel', () {
      expect(VpnContract.channel, 'io.github.evokelektrique.tunnelforge/vpn');
    });

    test('proxy exposure event contract', () {
      expect(VpnContract.onProxyExposureChanged, 'onProxyExposureChanged');
      expect(VpnContract.argProxyExposureActive, 'proxyExposureActive');
      expect(
        VpnContract.argProxyExposureBindAddress,
        'proxyExposureBindAddress',
      );
      expect(
        VpnContract.argProxyExposureDisplayAddress,
        'proxyExposureDisplayAddress',
      );
      expect(VpnContract.argProxyExposureHttpPort, 'proxyExposureHttpPort');
      expect(VpnContract.argProxyExposureSocksPort, 'proxyExposureSocksPort');
      expect(
        VpnContract.argProxyExposureLanRequested,
        'proxyExposureLanRequested',
      );
      expect(VpnContract.argProxyExposureLanActive, 'proxyExposureLanActive');
      expect(VpnContract.argProxyExposureWarning, 'proxyExposureWarning');
    });
  });

  group('VpnClient', () {
    late List<MethodCall> calls;

    setUp(() {
      calls = [];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(const MethodChannel(VpnContract.channel), (
            call,
          ) async {
            calls.add(call);
            switch (call.method) {
              case VpnContract.prepareVpn:
                return true;
              case VpnContract.setLogLevel:
              case VpnContract.connect:
              case VpnContract.disconnect:
              case VpnContract.onEngineLog:
                return null;
              case VpnContract.listVpnCandidateApps:
                return <Map<String, String>>[];
              case VpnContract.getAppIcon:
                return Uint8List.fromList([0x89, 0x50, 0x4e, 0x47]);
              default:
                fail('unexpected method ${call.method}');
            }
          });
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            const MethodChannel(VpnContract.channel),
            null,
          );
    });

    test('prepareVpn', () async {
      final client = VpnClient();
      final ok = await client.prepareVpn();
      expect(ok, isTrue);
      expect(calls, hasLength(1));
      expect(calls.single.method, VpnContract.prepareVpn);
    });

    test('connect map', () async {
      final client = VpnClient();
      await client.connect(
        server: '10.0.0.1',
        user: 'u',
        password: 'p',
        psk: 's',
        dnsAutomatic: false,
        dnsServers: const [
          DnsServerConfig(host: '1.1.1.1', protocol: DnsProtocol.dnsOverUdp),
        ],
      );
      expect(calls.single.method, VpnContract.connect);
      final args = calls.single.arguments as Map;
      expect(args[VpnContract.argServer], '10.0.0.1');
      expect(args[VpnContract.argUser], 'u');
      expect(args[VpnContract.argPassword], 'p');
      expect(args[VpnContract.argPsk], 's');
      expect(args[VpnContract.argDnsAutomatic], isFalse);
      expect(args[VpnContract.argDnsServers], [
        {
          VpnContract.argDnsServerHost: '1.1.1.1',
          VpnContract.argDnsServerProtocol: DnsProtocol.dnsOverUdp.jsonValue,
        },
      ]);
      expect(args[VpnContract.argMtu], Profile.defaultVpnMtu);
      expect(
        args[VpnContract.argConnectionMode],
        ConnectionMode.vpnTunnel.jsonValue,
      );
      expect(args[VpnContract.argSplitTunnelEnabled], isFalse);
      expect(
        args[VpnContract.argSplitTunnelMode],
        SplitTunnelMode.inclusive.jsonValue,
      );
      expect(
        args[VpnContract.argSplitTunnelInclusivePackages],
        isA<List<Object?>>(),
      );
      expect(
        (args[VpnContract.argSplitTunnelInclusivePackages] as List).isEmpty,
        isTrue,
      );
      expect(
        args[VpnContract.argSplitTunnelExclusivePackages],
        isA<List<Object?>>(),
      );
      expect(
        (args[VpnContract.argSplitTunnelExclusivePackages] as List).isEmpty,
        isTrue,
      );
      expect(args[VpnContract.argProxyHttpPort], ProxySettings.defaultHttpPort);
      expect(
        args[VpnContract.argProxySocksPort],
        ProxySettings.defaultSocksPort,
      );
      expect(args[VpnContract.argProxyAllowLan], isFalse);
    });

    test('connect map includes split-tunnel settings', () async {
      final client = VpnClient();
      await client.connect(
        server: '10.0.0.1',
        splitTunnelSettings: const SplitTunnelSettings(
          enabled: true,
          mode: SplitTunnelMode.exclusive,
          inclusivePackages: ['com.example.a'],
          exclusivePackages: ['com.example.b'],
        ),
      );
      final args = calls.single.arguments as Map;
      expect(args[VpnContract.argSplitTunnelEnabled], isTrue);
      expect(
        args[VpnContract.argSplitTunnelMode],
        SplitTunnelMode.exclusive.jsonValue,
      );
      expect(args[VpnContract.argSplitTunnelInclusivePackages], [
        'com.example.a',
      ]);
      expect(args[VpnContract.argSplitTunnelExclusivePackages], [
        'com.example.b',
      ]);
    });

    test('connect map includes proxy-only settings', () async {
      final client = VpnClient();
      await client.connect(
        server: '10.0.0.1',
        connectionMode: ConnectionMode.proxyOnly,
        proxySettings: const ProxySettings(
          httpPort: 18080,
          socksPort: 11080,
          allowLanConnections: true,
        ),
      );
      final args = calls.single.arguments as Map;
      expect(
        args[VpnContract.argConnectionMode],
        ConnectionMode.proxyOnly.jsonValue,
      );
      expect(args[VpnContract.argProxyHttpPort], 18080);
      expect(args[VpnContract.argProxySocksPort], 11080);
      expect(args[VpnContract.argProxyAllowLan], isTrue);
    });

    test('listVpnCandidateApps maps rows', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(const MethodChannel(VpnContract.channel), (
            call,
          ) async {
            if (call.method == VpnContract.listVpnCandidateApps) {
              return [
                {'packageName': 'a.b', 'label': 'App B'},
                {'packageName': 'bad', 'label': 1},
              ];
            }
            return null;
          });
      final client = VpnClient();
      final apps = await client.listVpnCandidateApps();
      expect(apps, hasLength(1));
      expect(apps.single.packageName, 'a.b');
      expect(apps.single.label, 'App B');
    });

    test('getAppIcon', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(const MethodChannel(VpnContract.channel), (
            call,
          ) async {
            expect(call.method, VpnContract.getAppIcon);
            expect(call.arguments, 'com.example.app');
            return Uint8List.fromList([1, 2, 3]);
          });
      final client = VpnClient();
      final bytes = await client.getAppIcon('com.example.app');
      expect(bytes, Uint8List.fromList([1, 2, 3]));
    });

    test('disconnect', () async {
      final client = VpnClient();
      await client.disconnect();
      expect(calls.single.method, VpnContract.disconnect);
    });

    test('tunnel state callback parses attempt id when present', () async {
      String? seenState;
      String? seenDetail;
      String? seenAttemptId;
      final client = VpnClient(
        onTunnelState: (state, detail, attemptId) {
          seenState = state;
          seenDetail = detail;
          seenAttemptId = attemptId;
        },
      );
      const codec = StandardMethodCodec();
      final data = codec.encodeMethodCall(
        const MethodCall(VpnContract.onTunnelState, <String, Object?>{
          VpnContract.argTunnelState: VpnTunnelState.failed,
          VpnContract.argTunnelDetail: 'L2TP handshake failed.',
          VpnContract.argAttemptId: 'attempt-2',
        }),
      );
      await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .handlePlatformMessage(VpnContract.channel, data, (ByteData? _) {});
      await Future<void>.delayed(Duration.zero);
      client.dispose();

      expect(seenState, VpnTunnelState.failed);
      expect(seenDetail, 'L2TP handshake failed.');
      expect(seenAttemptId, 'attempt-2');
    });

    test('setLogLevel', () async {
      final client = VpnClient();
      await client.setLogLevel(LogDisplayLevel.warning);
      expect(calls.single.method, VpnContract.setLogLevel);
      expect(calls.single.arguments, <String, Object?>{
        VpnContract.argLogLevel: 'warning',
      });
    });

    test('engine log callback parses source when present', () async {
      LogLevel? seenLevel;
      LogSource? seenSource;
      String? seenTag;
      String? seenMessage;
      final client = VpnClient(
        onEngineLog: (level, source, tag, message) {
          seenLevel = level;
          seenSource = source;
          seenTag = tag;
          seenMessage = message;
        },
      );
      const codec = StandardMethodCodec();
      final data = codec.encodeMethodCall(
        const MethodCall(VpnContract.onEngineLog, <String, Object?>{
          VpnContract.argEngineLogLevel: 5,
          VpnContract.argEngineLogSource: 'native',
          VpnContract.argEngineLogTag: 'tunnel_engine',
          VpnContract.argEngineLogMessage: 'hello',
        }),
      );
      await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .handlePlatformMessage(VpnContract.channel, data, (ByteData? _) {});
      await Future<void>.delayed(Duration.zero);
      client.dispose();

      expect(seenLevel, LogLevel.warning);
      expect(seenSource, LogSource.native);
      expect(seenTag, 'tunnel_engine');
      expect(seenMessage, 'hello');
    });

    test('engine log callback defaults source to kotlin when absent', () async {
      LogSource? seenSource;
      final client = VpnClient(
        onEngineLog: (_, source, tag, message) {
          expect(tag, 'MainActivity');
          expect(message, 'hello');
          seenSource = source;
        },
      );
      const codec = StandardMethodCodec();
      final data = codec.encodeMethodCall(
        const MethodCall(VpnContract.onEngineLog, <String, Object?>{
          VpnContract.argEngineLogLevel: 4,
          VpnContract.argEngineLogTag: 'MainActivity',
          VpnContract.argEngineLogMessage: 'hello',
        }),
      );
      await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .handlePlatformMessage(VpnContract.channel, data, (ByteData? _) {});
      await Future<void>.delayed(Duration.zero);
      client.dispose();

      expect(seenSource, LogSource.kotlin);
    });

    test('proxy exposure callback parses runtime address', () async {
      ProxyExposure? seenExposure;
      final client = VpnClient(
        onProxyExposureChanged: (exposure) {
          seenExposure = exposure;
        },
      );
      const codec = StandardMethodCodec();
      final data = codec.encodeMethodCall(
        const MethodCall(VpnContract.onProxyExposureChanged, <String, Object?>{
          VpnContract.argProxyExposureActive: true,
          VpnContract.argProxyExposureBindAddress: '192.168.1.24',
          VpnContract.argProxyExposureDisplayAddress: '192.168.1.24',
          VpnContract.argProxyExposureHttpPort: 18080,
          VpnContract.argProxyExposureSocksPort: 11080,
          VpnContract.argProxyExposureLanRequested: true,
          VpnContract.argProxyExposureLanActive: true,
          VpnContract.argProxyExposureWarning: null,
        }),
      );
      await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .handlePlatformMessage(VpnContract.channel, data, (ByteData? _) {});
      await Future<void>.delayed(Duration.zero);
      client.dispose();

      expect(
        seenExposure,
        const ProxyExposure(
          active: true,
          bindAddress: '192.168.1.24',
          displayAddress: '192.168.1.24',
          httpPort: 18080,
          socksPort: 11080,
          lanRequested: true,
          lanActive: true,
        ),
      );
    });
  });
}
