abstract class OnboardingRepository {
  Future<int?> loadAcknowledgedVersion();
  Future<void> saveAcknowledgedVersion(int version);
}

const int kCurrentL2tpDisclosureVersion = 1;
const String kL2tpDisclosureAcknowledgedVersionPrefsKey =
    'l2tp_disclosure_ack_version_v1';
