import 'dart:convert';
import 'dart:typed_data';

import 'package:archive/archive.dart';

import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/features/profiles/data/profile_transfer_contract.dart';

class ProfileTransferEnvelope {
  const ProfileTransferEnvelope({
    required this.displayName,
    required this.server,
    required this.user,
    required this.password,
    required this.psk,
    required this.dnsAutomatic,
    required this.dns1Host,
    required this.dns1Protocol,
    required this.dns2Host,
    required this.dns2Protocol,
    required this.mtu,
    this.version = currentVersion,
  });

  static const int currentVersion = 3;
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
  final bool dnsAutomatic;
  final String dns1Host;
  final DnsProtocol dns1Protocol;
  final String dns2Host;
  final DnsProtocol dns2Protocol;
  final int mtu;

  Profile toProfile(String id) {
    return Profile(
      id: id,
      displayName: displayName.trim().isEmpty ? server.trim() : displayName,
      server: server.trim(),
      user: user,
      dnsAutomatic: dnsAutomatic,
      dns1Host: Profile.normalizeDnsServerForProtocol(dns1Host, dns1Protocol),
      dns1Protocol: dns1Protocol,
      dns2Host: Profile.normalizeDnsServerForProtocol(dns2Host, dns2Protocol),
      dns2Protocol: dns2Protocol,
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
    'dnsAutomatic': dnsAutomatic,
    'dns1Host': Profile.normalizeDnsServerForProtocol(dns1Host, dns1Protocol),
    'dns1Protocol': dns1Protocol.jsonValue,
    'dns2Host': Profile.normalizeDnsServerForProtocol(dns2Host, dns2Protocol),
    'dns2Protocol': dns2Protocol.jsonValue,
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
      dnsAutomatic: profile.dnsAutomatic,
      dns1Host: profile.dns1Host,
      dns1Protocol: profile.dns1Protocol,
      dns2Host: profile.dns2Host,
      dns2Protocol: profile.dns2Protocol,
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
      throw const FormatException('Invalid TunnelForge share link');
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
    final dnsAutomatic = _requireBool(map, 'dnsAutomatic');
    final dns1Host = _requireString(map, 'dns1Host');
    final dns1Protocol = DnsProtocol.fromJson(
      _requireString(map, 'dns1Protocol'),
    );
    final dns2Host = _requireString(map, 'dns2Host');
    final dns2Protocol = DnsProtocol.fromJson(
      _requireString(map, 'dns2Protocol'),
    );
    final mtu = _requireInt(map, 'mtu');
    if (server.trim().isEmpty) {
      throw const FormatException('Profile transfer server is required');
    }
    final invalidDns1 = Profile.invalidDnsServer(dns1Host, dns1Protocol);
    if (invalidDns1 != null) {
      throw FormatException(
        Profile.validationMessageForDnsServer('DNS 1', dns1Host, dns1Protocol),
      );
    }
    final invalidDns2 = Profile.invalidDnsServer(dns2Host, dns2Protocol);
    if (invalidDns2 != null) {
      throw FormatException(
        Profile.validationMessageForDnsServer('DNS 2', dns2Host, dns2Protocol),
      );
    }
    if (!dnsAutomatic &&
        Profile.orderedDnsServers(
          dns1Host: dns1Host,
          dns1Protocol: dns1Protocol,
          dns2Host: dns2Host,
          dns2Protocol: dns2Protocol,
        ).isEmpty) {
      throw const FormatException(
        'Manual DNS requires at least one DNS server',
      );
    }
    return ProfileTransferEnvelope(
      version: version,
      displayName: displayName,
      server: server,
      user: user,
      password: password,
      psk: psk,
      dnsAutomatic: dnsAutomatic,
      dns1Host: Profile.normalizeDnsServerForProtocol(dns1Host, dns1Protocol),
      dns1Protocol: dns1Protocol,
      dns2Host: Profile.normalizeDnsServerForProtocol(dns2Host, dns2Protocol),
      dns2Protocol: dns2Protocol,
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

  static bool _requireBool(Map<String, Object?> map, String key) {
    final value = map[key];
    if (value is! bool) {
      throw FormatException('Missing bool field: $key');
    }
    return value;
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
