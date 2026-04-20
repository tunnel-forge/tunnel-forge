import 'dart:convert';
import 'dart:typed_data';

import 'package:archive/archive.dart';

import 'profile_models.dart';
import 'profile_transfer_contract.dart';

class ProfileTransferEnvelope {
  const ProfileTransferEnvelope({
    required this.displayName,
    required this.server,
    required this.user,
    required this.password,
    required this.psk,
    required this.dns,
    required this.mtu,
    this.version = currentVersion,
  });

  static const int currentVersion = 1;
  static const String fileExtension = 'tfp';
  static const String mimeType = 'application/vnd.tunnelforge.profile+json';
  static const String uriScheme = 'tf';
  static const String uriHost = 'p';

  final int version;
  final String displayName;
  final String server;
  final String user;
  final String password;
  final String psk;
  final String dns;
  final int mtu;

  Profile toProfile(String id) {
    return Profile(
      id: id,
      displayName: displayName.trim().isEmpty ? server.trim() : displayName,
      server: server.trim(),
      user: user,
      dns: Profile.normalizeDns(dns),
      mtu: Profile.normalizeMtu(mtu),
    );
  }

  Map<String, Object?> toJson() => <String, Object?>{
    'v': version,
    'displayName': displayName,
    'server': server,
    'user': user,
    'password': password,
    'psk': psk,
    'dns': Profile.normalizeDns(dns),
    'mtu': Profile.normalizeMtu(mtu),
  };

  String toFileJson() => const JsonEncoder.withIndent('  ').convert(toJson());

  String toTfUri() {
    final jsonBytes = utf8.encode(jsonEncode(toJson()));
    final compressed = GZipEncoder().encode(jsonBytes);
    final payload = base64UrlEncode(compressed).replaceAll('=', '');
    return '$uriScheme://$uriHost/$payload';
  }

  static ProfileTransferEnvelope fromProfile({
    required Profile profile,
    required String password,
    required String psk,
  }) {
    return ProfileTransferEnvelope(
      displayName: profile.displayName,
      server: profile.server,
      user: profile.user,
      password: password,
      psk: psk,
      dns: profile.dns,
      mtu: profile.mtu,
    );
  }

  static ProfileTransferEnvelope fromIncomingTransfer(
    IncomingProfileTransfer transfer,
  ) {
    final data = transfer.data;
    if (data == null || data.isEmpty) {
      throw const FormatException('Incoming profile transfer is empty');
    }
    return switch (transfer.type) {
      ProfileTransferContract.typeTfpJson => fromFileJson(data),
      ProfileTransferContract.typeTfUri => fromTfUri(data),
      _ => throw FormatException(transfer.message ?? 'Unsupported transfer'),
    };
  }

  static ProfileTransferEnvelope fromFileJson(String text) {
    final decoded = jsonDecode(text);
    if (decoded is! Map) {
      throw const FormatException('Profile file must contain a JSON object');
    }
    return fromJsonMap(Map<String, Object?>.from(decoded));
  }

  static ProfileTransferEnvelope fromTfUri(String text) {
    final uri = Uri.tryParse(text.trim());
    if (uri == null || uri.scheme != uriScheme || uri.host != uriHost) {
      throw const FormatException('Invalid Tunnel Forge share link');
    }
    if (uri.pathSegments.isEmpty) {
      throw const FormatException('Share link payload is missing');
    }
    final payload = uri.pathSegments.join('');
    final normalized = base64Url.normalize(payload);
    final compressed = base64Url.decode(normalized);
    final jsonBytes = Uint8List.fromList(GZipDecoder().decodeBytes(compressed));
    return fromFileJson(utf8.decode(jsonBytes));
  }

  static ProfileTransferEnvelope fromJsonMap(Map<String, Object?> map) {
    final version = map['v'];
    if (version is! int || version != currentVersion) {
      throw FormatException('Unsupported profile transfer version: $version');
    }
    final displayName = _requireString(map, 'displayName');
    final server = _requireString(map, 'server');
    final user = _requireString(map, 'user');
    final password = _requireString(map, 'password');
    final psk = _requireString(map, 'psk');
    final dns = _requireString(map, 'dns');
    final mtu = _requireInt(map, 'mtu');
    if (server.trim().isEmpty) {
      throw const FormatException('Profile transfer server is required');
    }
    final invalidDns = Profile.firstInvalidDnsServer(dns);
    if (invalidDns != null) {
      throw FormatException('Invalid IPv4 DNS server: $invalidDns');
    }
    return ProfileTransferEnvelope(
      version: version,
      displayName: displayName,
      server: server,
      user: user,
      password: password,
      psk: psk,
      dns: dns,
      mtu: mtu,
    );
  }

  static String exportFileNameFor(Profile profile) {
    final base = _sanitizeFileName(
      profile.displayName.trim().isEmpty ? profile.server : profile.displayName,
    );
    final fallback = base.isEmpty ? 'tunnel-forge-profile' : base;
    return '$fallback.$fileExtension';
  }

  static String _sanitizeFileName(String value) {
    return value
        .trim()
        .replaceAll(RegExp(r'[\\/:*?"<>|]+'), '-')
        .replaceAll(RegExp(r'\s+'), '-')
        .replaceAll(RegExp(r'-+'), '-')
        .replaceAll(RegExp(r'^-+|-+$'), '')
        .toLowerCase();
  }

  static int _requireInt(Map<String, Object?> map, String key) {
    final value = map[key];
    return switch (value) {
      int v => v,
      num v => v.toInt(),
      String v =>
        int.tryParse(v.trim()) ??
            (throw FormatException('Invalid integer field: $key')),
      _ => throw FormatException('Missing integer field: $key'),
    };
  }

  static String _requireString(Map<String, Object?> map, String key) {
    final value = map[key];
    if (value is! String) {
      throw FormatException('Missing string field: $key');
    }
    return value;
  }
}

class IncomingProfileTransfer {
  const IncomingProfileTransfer({
    required this.type,
    this.data,
    this.message,
    this.source,
  });

  final String type;
  final String? data;
  final String? message;
  final String? source;

  bool get isError => type == ProfileTransferContract.typeError;

  static IncomingProfileTransfer? tryFromMap(Object? raw) {
    if (raw is! Map) return null;
    final map = Map<String, Object?>.from(raw);
    final type = map[ProfileTransferContract.argType];
    if (type is! String || type.isEmpty) return null;
    return IncomingProfileTransfer(
      type: type,
      data: map[ProfileTransferContract.argData] as String?,
      message: map[ProfileTransferContract.argMessage] as String?,
      source: map[ProfileTransferContract.argSource] as String?,
    );
  }
}
