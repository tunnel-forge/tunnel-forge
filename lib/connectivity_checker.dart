import 'dart:async';
import 'dart:io';

import 'profile_models.dart';

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

abstract class ConnectivityChecker {
  Future<ConnectivityPingResult> ping(String url);
}

class DirectConnectivityChecker implements ConnectivityChecker {
  DirectConnectivityChecker({
    this.timeout = const Duration(seconds: 6),
    HttpClient Function()? clientFactory,
  }) : _clientFactory = clientFactory ?? HttpClient.new;

  final Duration timeout;
  final HttpClient Function() _clientFactory;

  @override
  Future<ConnectivityPingResult> ping(String url) async {
    final normalizedUrl = ConnectivityCheckSettings.normalizeUrl(url);
    final validationError = ConnectivityCheckSettings.validateUrl(
      normalizedUrl,
    );
    if (validationError != null) {
      return ConnectivityPingResult.failure(error: validationError);
    }

    final uri = Uri.parse(normalizedUrl);
    final client = _clientFactory();
    client.connectionTimeout = timeout;
    client.findProxy = (_) => 'DIRECT';

    final watch = Stopwatch()..start();

    try {
      final request = await client.getUrl(uri).timeout(timeout);
      request.followRedirects = true;
      final response = await request.close().timeout(timeout);
      await response.drain<void>().timeout(timeout);
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
}
