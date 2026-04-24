import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tunnel_forge/features/onboarding/domain/app_exit_controller.dart';
import 'package:tunnel_forge/features/onboarding/domain/onboarding_repository.dart';
import 'package:tunnel_forge/features/onboarding/presentation/bloc/onboarding_bloc.dart';
import 'package:tunnel_forge/features/onboarding/presentation/pages/onboarding_page.dart';
import 'package:tunnel_forge/main.dart';
import 'package:tunnel_forge/profile_store.dart';

class _FakeOnboardingRepository implements OnboardingRepository {
  _FakeOnboardingRepository({this.version});

  int? version;
  int? savedVersion;

  @override
  Future<int?> loadAcknowledgedVersion() async => version;

  @override
  Future<void> saveAcknowledgedVersion(int version) async {
    savedVersion = version;
    this.version = version;
  }
}

class _FakeAppExitController implements AppExitController {
  int closeCalls = 0;
  final Completer<void> closeCompleter = Completer<void>();

  @override
  Future<void> closeApp() async {
    closeCalls += 1;
    if (!closeCompleter.isCompleted) {
      closeCompleter.complete();
    }
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  Future<void> pumpApp(
    WidgetTester tester, {
    required OnboardingRepository onboardingRepository,
    required AppExitController appExitController,
  }) async {
    SharedPreferences.setMockInitialValues({});
    await tester.binding.setSurfaceSize(const Size(480, 1200));
    addTearDown(() async {
      await tester.binding.setSurfaceSize(null);
    });
    await tester.pumpWidget(
      TunnelForgeApp(
        profileStore: ProfileStore(secretsOverride: MemorySecretStore()),
        onboardingRepository: onboardingRepository,
        appExitController: appExitController,
      ),
    );
    await tester.pump();
  }

  testWidgets('first launch shows onboarding instead of home', (tester) async {
    await pumpApp(
      tester,
      onboardingRepository: _FakeOnboardingRepository(),
      appExitController: _FakeAppExitController(),
    );

    expect(find.byKey(const Key('onboarding_title')), findsOneWidget);
    expect(find.byKey(const Key('vpn_status')), findsNothing);
    expect(find.byKey(const Key('onboarding_cancel_button')), findsNothing);
    expect(find.text('Back'), findsNothing);
  });

  testWidgets('accepted onboarding skips directly to home', (tester) async {
    await pumpApp(
      tester,
      onboardingRepository: _FakeOnboardingRepository(
        version: kCurrentL2tpDisclosureVersion,
      ),
      appExitController: _FakeAppExitController(),
    );
    await tester.pump(const Duration(milliseconds: 100));

    expect(find.byKey(const Key('vpn_status')), findsOneWidget);
    expect(find.byKey(const Key('onboarding_title')), findsNothing);
  });

  testWidgets(
    'agree unlocks only after checkbox and countdown then opens home',
    (tester) async {
      final repository = _FakeOnboardingRepository();
      await pumpApp(
        tester,
        onboardingRepository: repository,
        appExitController: _FakeAppExitController(),
      );

      expect(find.text('Continue'), findsOneWidget);
      expect(find.text('Choose your language'), findsOneWidget);

      await tester.ensureVisible(
        find.byKey(const Key('onboarding_continue_button')),
      );
      await tester.tap(find.byKey(const Key('onboarding_continue_button')));
      await tester.pump(const Duration(milliseconds: 500));
      expect(find.text('TunnelForge'), findsOneWidget);

      await tester.ensureVisible(
        find.byKey(const Key('onboarding_continue_button')),
      );
      await tester.tap(find.byKey(const Key('onboarding_continue_button')));
      await tester.pump(const Duration(milliseconds: 500));
      expect(find.text('Review security notice'), findsOneWidget);
      expect(
        find.text('Confirm in ${kOnboardingAgreeCountdownSeconds}s'),
        findsOneWidget,
      );

      await tester.ensureVisible(
        find.byKey(const Key('onboarding_agree_button')),
      );
      await tester.tap(find.byKey(const Key('onboarding_agree_button')));
      await tester.pump();
      expect(repository.savedVersion, isNull);

      await tester.ensureVisible(
        find.text('I understand the L2TP risk.'),
      );
      await tester.tap(
        find.text('I understand the L2TP risk.'),
      );
      await tester.pump();
      expect(
        find.text('Confirm in ${kOnboardingAgreeCountdownSeconds}s'),
        findsOneWidget,
      );

      await tester.pump(
        const Duration(seconds: kOnboardingAgreeCountdownSeconds),
      );
      await tester.pump();
      expect(find.text('Confirm'), findsOneWidget);

      await tester.ensureVisible(
        find.byKey(const Key('onboarding_agree_button')),
      );
      await tester.tap(find.byKey(const Key('onboarding_agree_button')));
      await tester.pump();
      for (var i = 0; i < 20; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }

      expect(repository.savedVersion, kCurrentL2tpDisclosureVersion);
    },
  );

  testWidgets('blocking onboarding has no cancel affordance', (tester) async {
    await pumpApp(
      tester,
      onboardingRepository: _FakeOnboardingRepository(),
      appExitController: _FakeAppExitController(),
    );

    expect(find.byKey(const Key('onboarding_cancel_button')), findsNothing);
    expect(find.text('Cancel'), findsNothing);
    expect(find.text('Back'), findsNothing);
  });

  testWidgets('read-only notice can be dismissed', (tester) async {
    final bloc = OnboardingBloc(
      _FakeOnboardingRepository(),
      _FakeAppExitController(),
    )..add(const OnboardingReadOnlyOpened());
    addTearDown(bloc.close);
    final navigatorKey = GlobalKey<NavigatorState>();

    await tester.pumpWidget(
      MaterialApp(
        navigatorKey: navigatorKey,
        home: const Scaffold(body: Text('Home placeholder')),
      ),
    );
    await tester.pump();
    navigatorKey.currentState!.push(
      MaterialPageRoute<void>(
        builder: (context) => BlocProvider<OnboardingBloc>.value(
          value: bloc,
          child: const OnboardingPage(),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));

    expect(find.text('Notice'), findsOneWidget);
    expect(find.byKey(const Key('onboarding_checkbox')), findsNothing);
    expect(find.text('Dismiss'), findsOneWidget);

    await tester.ensureVisible(
      find.byKey(const Key('onboarding_cancel_button')),
    );
    await tester.tap(find.byKey(const Key('onboarding_cancel_button')));
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));

    expect(find.text('Notice'), findsNothing);
    expect(find.text('Home placeholder'), findsOneWidget);
  });
}
