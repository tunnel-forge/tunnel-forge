import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:tunnel_forge/features/home/data/home_repositories_impl.dart';
import 'package:tunnel_forge/features/home/domain/home_models.dart';

void main() {
  group('AppVersionRepositoryImpl', () {
    test('combines app version and build number for display', () async {
      final repository = AppVersionRepositoryImpl(
        packageInfoLoader: () async => PackageInfo(
          appName: 'Tunnel Forge',
          packageName: 'io.github.evokelektrique.tunnelforge',
          version: '0.3.0',
          buildNumber: '11',
          buildSignature: '',
          installerStore: null,
        ),
      );

      final version = await repository.loadInstalledVersion();

      expect(version.displayVersion, '0.3.0+11');
      expect(version.semanticVersion?.toString(), '0.3.0');
    });

    test(
      'falls back to the native app info channel when package_info_plus fails',
      () async {
        final repository = AppVersionRepositoryImpl(
          packageInfoLoader: () async =>
              throw PlatformException(code: 'missing'),
          nativeVersionLoader: () async =>
              (versionName: '0.3.0', buildNumber: '11'),
        );

        final version = await repository.loadInstalledVersion();

        expect(version.displayVersion, '0.3.0+11');
        expect(version.semanticVersion?.toString(), '0.3.0');
        expect(version.errorReason, isNull);
      },
    );

    test(
      'returns an actionable reason when both version sources fail',
      () async {
        final repository = AppVersionRepositoryImpl(
          packageInfoLoader: () async =>
              throw PlatformException(code: 'missing'),
          nativeVersionLoader: () async =>
              throw PlatformException(code: 'native'),
        );

        final version = await repository.loadInstalledVersion();

        expect(version.displayVersion, isNull);
        expect(version.semanticVersion, isNull);
        expect(version.errorReason, contains('Installed version unavailable.'));
      },
    );
  });

  group('AppUpdateRepositoryImpl', () {
    test(
      'selects the newest published valid release and keeps prereleases',
      () async {
        final repository = AppUpdateRepositoryImpl(
          fetcher: (_) async => '''
[
  {
    "tag_name": "v0.1.6",
    "html_url": "https://github.com/evokelektrique/tunnel-forge/releases/tag/v0.1.6",
    "published_at": "2026-04-19T22:08:40Z",
    "draft": false,
    "prerelease": true
  },
  {
    "tag_name": "v0.1.5",
    "html_url": "https://github.com/evokelektrique/tunnel-forge/releases/tag/v0.1.5",
    "published_at": "2026-04-01T12:00:00Z",
    "draft": false,
    "prerelease": false
  },
  {
    "tag_name": "not-a-version",
    "html_url": "https://github.com/evokelektrique/tunnel-forge/releases/tag/bad",
    "published_at": "2026-04-22T12:00:00Z",
    "draft": false,
    "prerelease": false
  }
]
''',
        );

        final release = await repository.fetchLatestRelease();

        expect(release.versionLabel, '0.1.6');
        expect(release.prerelease, isTrue);
      },
    );

    test('maps malformed responses to actionable update errors', () async {
      final repository = AppUpdateRepositoryImpl(fetcher: (_) async => '{');

      expect(
        repository.fetchLatestRelease(),
        throwsA(
          isA<AppUpdateException>().having(
            (error) => error.userMessage,
            'userMessage',
            'GitHub Releases returned malformed data.',
          ),
        ),
      );
    });
  });
}
