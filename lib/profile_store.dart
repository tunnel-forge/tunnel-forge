// Profile list JSON in [SharedPreferences]; password and PSK in [SecretStore].
import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'profile_models.dart';
import 'profile_transfer.dart';
import 'utils/log_entry.dart';

/// Persists sensitive strings (password, PSK) outside [SharedPreferences].
abstract class SecretStore {
  Future<String?> read(String key);
  Future<void> write(String key, String value);
  Future<void> delete(String key);
}

/// Production [SecretStore] backed by [FlutterSecureStorage].
class FlutterSecureSecretStore implements SecretStore {
  FlutterSecureSecretStore([FlutterSecureStorage? storage])
    : _storage = storage ?? const FlutterSecureStorage();

  final FlutterSecureStorage _storage;

  @override
  Future<void> delete(String key) => _storage.delete(key: key);

  @override
  Future<String?> read(String key) => _storage.read(key: key);

  @override
  Future<void> write(String key, String value) =>
      _storage.write(key: key, value: value);
}

/// In-memory [SecretStore] for tests and widget tests.
class MemorySecretStore implements SecretStore {
  final Map<String, String> _data = {};

  @override
  Future<void> delete(String key) async => _data.remove(key);

  @override
  Future<String?> read(String key) async => _data[key];

  @override
  Future<void> write(String key, String value) async => _data[key] = value;
}

/// Loads, saves, and deletes [Profile] rows; tests may inject [prefsOverride] / [secretsOverride].
class ProfileStore {
  ProfileStore({SharedPreferences? prefsOverride, SecretStore? secretsOverride})
    : _prefsOverride = prefsOverride,
      _secrets = secretsOverride ?? FlutterSecureSecretStore();

  static const prefsKeyProfilesJson = 'vpn_profiles_json_v3';
  static const prefsKeyLastProfileId = 'vpn_last_profile_id_v1';
  static const prefsKeyConnectionMode = 'connection_mode_v1';
  static const prefsKeyProxyHttpPort = 'proxy_http_port_v1';
  static const prefsKeyProxySocksPort = 'proxy_socks_port_v1';
  static const prefsKeyProxyAllowLanConnections =
      'proxy_allow_lan_connections_v1';
  static const prefsKeyConnectivityCheckUrl = 'connectivity_check_url_v1';
  static const prefsKeyConnectivityCheckTimeoutMs =
      'connectivity_check_timeout_ms_v1';
  static const prefsKeyLogDisplayLevel = 'log_display_level_v1';

  final SharedPreferences? _prefsOverride;
  final SecretStore _secrets;

  String _passwordKey(String profileId) =>
      'tunnel_forge/profile/$profileId/password';
  String _pskKey(String profileId) => 'tunnel_forge/profile/$profileId/psk';

  Future<SharedPreferences> _prefs() async =>
      _prefsOverride ?? SharedPreferences.getInstance();

  static String newProfileId() {
    final b = Uint8List.fromList(
      List<int>.generate(16, (_) => Random.secure().nextInt(256)),
    );
    return base64UrlEncode(b).replaceAll('=', '');
  }

  Future<List<Profile>> loadProfiles() async {
    final p = await _prefs();
    final raw = p.getString(prefsKeyProfilesJson);
    if (raw == null || raw.isEmpty) return [];
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! List) return [];
      final out = <Profile>[];
      for (final e in decoded) {
        final row = Profile.tryFromJson(e);
        if (row != null) out.add(row);
      }
      return out;
    } catch (_) {
      return [];
    }
  }

  Future<String?> loadLastProfileId() async {
    final p = await _prefs();
    return p.getString(prefsKeyLastProfileId);
  }

  Future<void> setLastProfileId(String? id) async {
    final p = await _prefs();
    if (id == null || id.isEmpty) {
      await p.remove(prefsKeyLastProfileId);
    } else {
      await p.setString(prefsKeyLastProfileId, id);
    }
  }

  Future<ConnectionMode> loadConnectionMode() async {
    final p = await _prefs();
    return ConnectionMode.fromJson(p.getString(prefsKeyConnectionMode));
  }

  Future<void> saveConnectionMode(ConnectionMode mode) async {
    final p = await _prefs();
    await p.setString(prefsKeyConnectionMode, mode.jsonValue);
  }

  Future<ProxySettings> loadProxySettings() async {
    final p = await _prefs();
    return ProxySettings(
      httpPort: ProxySettings.normalizePort(
        p.getInt(prefsKeyProxyHttpPort) ?? ProxySettings.defaultHttpPort,
        fallback: ProxySettings.defaultHttpPort,
      ),
      socksPort: ProxySettings.normalizePort(
        p.getInt(prefsKeyProxySocksPort) ?? ProxySettings.defaultSocksPort,
        fallback: ProxySettings.defaultSocksPort,
      ),
      allowLanConnections: p.getBool(prefsKeyProxyAllowLanConnections) ?? false,
    );
  }

  Future<void> saveProxySettings(ProxySettings settings) async {
    final p = await _prefs();
    await p.setInt(
      prefsKeyProxyHttpPort,
      ProxySettings.normalizePort(
        settings.httpPort,
        fallback: ProxySettings.defaultHttpPort,
      ),
    );
    await p.setInt(
      prefsKeyProxySocksPort,
      ProxySettings.normalizePort(
        settings.socksPort,
        fallback: ProxySettings.defaultSocksPort,
      ),
    );
    await p.setBool(
      prefsKeyProxyAllowLanConnections,
      settings.allowLanConnections,
    );
  }

  Future<ConnectivityCheckSettings> loadConnectivityCheckSettings() async {
    final p = await _prefs();
    return ConnectivityCheckSettings(
      url: ConnectivityCheckSettings.normalizeUrl(
        p.getString(prefsKeyConnectivityCheckUrl) ??
            ConnectivityCheckSettings.defaultUrl,
      ),
      timeoutMs: ConnectivityCheckSettings.normalizeTimeoutMs(
        p.getInt(prefsKeyConnectivityCheckTimeoutMs) ??
            ConnectivityCheckSettings.defaultTimeoutMs,
      ),
    );
  }

  Future<void> saveConnectivityCheckSettings(
    ConnectivityCheckSettings settings,
  ) async {
    final p = await _prefs();
    await p.setString(
      prefsKeyConnectivityCheckUrl,
      ConnectivityCheckSettings.normalizeUrl(settings.url),
    );
    await p.setInt(
      prefsKeyConnectivityCheckTimeoutMs,
      ConnectivityCheckSettings.normalizeTimeoutMs(settings.timeoutMs),
    );
  }

  Future<LogDisplayLevel> loadLogDisplayLevel() async {
    final p = await _prefs();
    return LogDisplayLevel.fromStorage(p.getString(prefsKeyLogDisplayLevel));
  }

  Future<void> saveLogDisplayLevel(LogDisplayLevel level) async {
    final p = await _prefs();
    await p.setString(prefsKeyLogDisplayLevel, level.storageValue);
  }

  Future<void> _saveProfileList(List<Profile> list) async {
    final p = await _prefs();
    await p.setString(
      prefsKeyProfilesJson,
      jsonEncode(list.map((e) => e.toJson()).toList()),
    );
  }

  Future<void> upsertProfile(
    Profile profile, {
    required String password,
    required String psk,
  }) async {
    final list = await loadProfiles();
    final idx = list.indexWhere((e) => e.id == profile.id);
    if (idx >= 0) {
      list[idx] = profile;
    } else {
      list.add(profile);
    }
    await _saveProfileList(list);
    await _secrets.write(_passwordKey(profile.id), password);
    await _secrets.write(_pskKey(profile.id), psk);
    await setLastProfileId(profile.id);
  }

  Future<Profile> saveImportedProfile(
    ProfileTransferEnvelope envelope, {
    bool selectAsLastProfile = true,
  }) async {
    final imported = envelope.toProfile(newProfileId());
    final list = await loadProfiles()
      ..add(imported);
    await _saveProfileList(list);
    await _secrets.write(_passwordKey(imported.id), envelope.password);
    await _secrets.write(_pskKey(imported.id), envelope.psk);
    if (selectAsLastProfile) {
      await setLastProfileId(imported.id);
    }
    return imported;
  }

  Future<void> deleteProfile(String id) async {
    if (id.isEmpty) return;
    final list = await loadProfiles();
    list.removeWhere((e) => e.id == id);
    await _saveProfileList(list);
    await _secrets.delete(_passwordKey(id));
    await _secrets.delete(_pskKey(id));
    final last = await loadLastProfileId();
    if (last == id) await setLastProfileId(null);
  }

  Future<({Profile profile, String password, String psk})?>
  loadProfileWithSecrets(String id) async {
    final list = await loadProfiles();
    Profile? profile;
    for (final p in list) {
      if (p.id == id) {
        profile = p;
        break;
      }
    }
    if (profile == null) return null;
    final password = await _secrets.read(_passwordKey(id)) ?? '';
    final psk = await _secrets.read(_pskKey(id)) ?? '';
    return (profile: profile, password: password, psk: psk);
  }
}
