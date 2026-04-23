import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tunnel_forge/features/onboarding/data/shared_prefs_onboarding_repository.dart';
import 'package:tunnel_forge/features/onboarding/domain/onboarding_repository.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('loadAcknowledgedVersion returns null when missing', () async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    final repository = SharedPrefsOnboardingRepository(prefsOverride: prefs);

    expect(await repository.loadAcknowledgedVersion(), isNull);
  });

  test(
    'saveAcknowledgedVersion persists the current disclosure version',
    () async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();
      final repository = SharedPrefsOnboardingRepository(prefsOverride: prefs);

      await repository.saveAcknowledgedVersion(kCurrentL2tpDisclosureVersion);

      expect(
        await repository.loadAcknowledgedVersion(),
        kCurrentL2tpDisclosureVersion,
      );
      expect(
        prefs.getInt(kL2tpDisclosureAcknowledgedVersionPrefsKey),
        kCurrentL2tpDisclosureVersion,
      );
    },
  );
}
