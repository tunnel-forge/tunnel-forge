import 'dart:async';
import 'dart:io';

import 'package:equatable/equatable.dart';

import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';

class ConnectivityPingResult {
  const ConnectivityPingResult({
    required this.reachable,
    this.latencyMs,
    this.statusCode,
    this.error,
  });

  final bool reachable;
  final int? latencyMs;
  final int? statusCode;
  final String? error;

  factory ConnectivityPingResult.success({
    required int latencyMs,
    required int statusCode,
  }) {
    return ConnectivityPingResult(
      reachable: true,
      latencyMs: latencyMs,
      statusCode: statusCode,
    );
  }

  factory ConnectivityPingResult.failure({int? statusCode, String? error}) {
    return ConnectivityPingResult(
      reachable: false,
      statusCode: statusCode,
      error: error,
    );
  }
}

enum ConnectivityPingRoute { direct, localHttpProxy }

class ConnectivityPingRequest extends Equatable {
  const ConnectivityPingRequest._({
    required this.url,
    required this.timeoutMs,
    required this.route,
    this.proxyHost,
    this.proxyPort,
  });

  const ConnectivityPingRequest.direct(String url)
    : this._(
        url: url,
        timeoutMs: ConnectivityCheckSettings.defaultTimeoutMs,
        route: ConnectivityPingRoute.direct,
      );

  const ConnectivityPingRequest.directWithTimeout({
    required String url,
    required int timeoutMs,
  }) : this._(
         url: url,
         timeoutMs: timeoutMs,
         route: ConnectivityPingRoute.direct,
       );

  const ConnectivityPingRequest.localHttpProxy({
    required String url,
    int timeoutMs = ConnectivityCheckSettings.defaultTimeoutMs,
    String proxyHost = _defaultProxyHost,
    required int proxyPort,
  }) : this._(
         url: url,
         timeoutMs: timeoutMs,
         route: ConnectivityPingRoute.localHttpProxy,
         proxyHost: proxyHost,
         proxyPort: proxyPort,
       );

  static const String _defaultProxyHost = '127.0.0.1';

  final String url;
  final int timeoutMs;
  final ConnectivityPingRoute route;
  final String? proxyHost;
  final int? proxyPort;

  @override
  List<Object?> get props => [url, timeoutMs, route, proxyHost, proxyPort];
}

abstract class ConnectivityChecker {
  Future<ConnectivityPingResult> ping(ConnectivityPingRequest request);
}

class HttpConnectivityChecker implements ConnectivityChecker {
  HttpConnectivityChecker({
    this.timeout = const Duration(milliseconds: 5000),
    HttpClient Function()? clientFactory,
  }) : _clientFactory = clientFactory ?? HttpClient.new;

  final Duration timeout;
  final HttpClient Function() _clientFactory;

  @override
  Future<ConnectivityPingResult> ping(ConnectivityPingRequest request) async {
    final normalizedUrl = ConnectivityCheckSettings.normalizeUrl(request.url);
    final validationError = ConnectivityCheckSettings.validateUrl(
      normalizedUrl,
    );
    if (validationError != null) {
      return ConnectivityPingResult.failure(error: validationError);
    }

    final uri = Uri.parse(normalizedUrl);
    final effectiveTimeout = request.timeoutMs > 0
        ? Duration(
            milliseconds: ConnectivityCheckSettings.normalizeTimeoutMs(
              request.timeoutMs,
            ),
          )
        : timeout;
    final client = _clientFactory();
    client.connectionTimeout = effectiveTimeout;
    client.findProxy = (_) => _proxyDirectiveFor(request);

    final watch = Stopwatch()..start();

    try {
      final httpRequest = await client.getUrl(uri).timeout(effectiveTimeout);
      httpRequest.followRedirects = true;
      final response = await httpRequest.close().timeout(effectiveTimeout);
      await response.drain<void>().timeout(effectiveTimeout);
      watch.stop();

      final code = response.statusCode;
      if (code >= 200 && code < 400) {
        return ConnectivityPingResult.success(
          latencyMs: watch.elapsedMilliseconds,
          statusCode: code,
        );
      }
      return ConnectivityPingResult.failure(
        statusCode: code,
        error: 'HTTP $code',
      );
    } on TimeoutException {
      return ConnectivityPingResult.failure(error: 'Timed out');
    } on SocketException catch (e) {
      return ConnectivityPingResult.failure(error: e.message);
    } on HttpException catch (e) {
      return ConnectivityPingResult.failure(error: e.message);
    } catch (e) {
      return ConnectivityPingResult.failure(error: '$e');
    } finally {
      client.close(force: true);
    }
  }

  String _proxyDirectiveFor(ConnectivityPingRequest request) {
    return switch (request.route) {
      ConnectivityPingRoute.direct => 'DIRECT',
      ConnectivityPingRoute.localHttpProxy => () {
        final host = request.proxyHost?.trim();
        final port = request.proxyPort;
        if (host == null ||
            host.isEmpty ||
            port == null ||
            port < ProxySettings.minPort ||
            port > ProxySettings.maxPort) {
          throw SocketException('Invalid local HTTP proxy configuration');
        }
        return 'PROXY $host:$port';
      }(),
    };
  }
}
