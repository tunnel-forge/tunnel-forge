import 'package:shared_preferences/shared_preferences.dart';

import '../domain/onboarding_repository.dart';

class SharedPrefsOnboardingRepository implements OnboardingRepository {
  SharedPrefsOnboardingRepository({SharedPreferences? prefsOverride})
    : _prefsOverride = prefsOverride;

  final SharedPreferences? _prefsOverride;

  Future<SharedPreferences> _prefs() async {
    return _prefsOverride ?? SharedPreferences.getInstance();
  }

  @override
  Future<int?> loadAcknowledgedVersion() async {
    final prefs = await _prefs();
    return prefs.getInt(kL2tpDisclosureAcknowledgedVersionPrefsKey);
  }

  @override
  Future<void> saveAcknowledgedVersion(int version) async {
    final prefs = await _prefs();
    await prefs.setInt(kL2tpDisclosureAcknowledgedVersionPrefsKey, version);
  }
}
