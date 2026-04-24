import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:get_it/get_it.dart';

import 'package:tunnel_forge/core/network/connectivity_checker.dart';
import 'package:tunnel_forge/l10n/app_localizations.dart';
import 'package:tunnel_forge/features/profiles/data/profile_store.dart';
import 'package:tunnel_forge/features/profiles/data/profile_transfer_bridge.dart';
import 'package:tunnel_forge/app/theme/app_theme.dart';
import 'package:tunnel_forge/features/tunnel/data/vpn_client.dart';
import 'package:tunnel_forge/features/home/domain/home_repositories.dart';
import 'package:tunnel_forge/features/app_theme/presentation/bloc/app_theme_bloc.dart';
import 'package:tunnel_forge/features/home/presentation/pages/home_page.dart';
import 'package:tunnel_forge/features/onboarding/domain/app_exit_controller.dart';
import 'package:tunnel_forge/features/onboarding/domain/onboarding_repository.dart';
import 'package:tunnel_forge/features/onboarding/presentation/bloc/onboarding_bloc.dart';
import 'package:tunnel_forge/features/onboarding/presentation/pages/onboarding_page.dart';
import 'di/injection.dart';

class TunnelForgeApp extends StatefulWidget {
  const TunnelForgeApp({
    super.key,
    this.profileStore,
    this.connectivityChecker,
    this.vpnClient,
    this.profileTransferBridge,
    this.appVersionRepository,
    this.appUpdateRepository,
    this.onboardingRepository,
    this.appExitController,
  });

  final ProfileStore? profileStore;
  final ConnectivityChecker? connectivityChecker;
  final VpnClient? vpnClient;
  final ProfileTransferBridge? profileTransferBridge;
  final AppVersionRepository? appVersionRepository;
  final AppUpdateRepository? appUpdateRepository;
  final OnboardingRepository? onboardingRepository;
  final AppExitController? appExitController;

  @override
  State<TunnelForgeApp> createState() => _TunnelForgeAppState();
}

class _TunnelForgeAppState extends State<TunnelForgeApp> {
  late final GetIt _locator;
  late final AppLanguageController _languageController;

  @override
  void initState() {
    super.initState();
    _languageController = AppLanguageController();
    _locator = createAppLocator(
      profileStore: widget.profileStore,
      connectivityChecker: widget.connectivityChecker,
      vpnClient: widget.vpnClient,
      profileTransferBridge: widget.profileTransferBridge,
      appVersionRepository: widget.appVersionRepository,
      appUpdateRepository: widget.appUpdateRepository,
      onboardingRepository: widget.onboardingRepository,
      appExitController: widget.appExitController,
    );
  }

  @override
  void dispose() {
    disposeLocator(_locator);
    _languageController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AppLanguageScope(
      controller: _languageController,
      child: BlocProvider<AppThemeBloc>(
        create: (_) => _locator<AppThemeBloc>()..add(const AppThemeStarted()),
        child: BlocBuilder<AppThemeBloc, AppThemeState>(
          builder: (context, state) {
            return AnimatedBuilder(
              animation: _languageController,
              builder: (context, _) {
                final language = _languageController.language;
                return MaterialApp(
                  title: 'TunnelForge',
                  locale: language.locale,
                  supportedLocales: AppLocalizations.supportedLocales,
                  localizationsDelegates: const [
                    AppLocalizations.delegate,
                    GlobalMaterialLocalizations.delegate,
                    GlobalWidgetsLocalizations.delegate,
                    GlobalCupertinoLocalizations.delegate,
                  ],
                  theme: appTheme(Brightness.light),
                  darkTheme: appTheme(Brightness.dark),
                  themeMode: state.themeMode,
                  home: BlocProvider<OnboardingBloc>(
                    create: (_) =>
                        _locator<OnboardingBloc>()
                          ..add(const OnboardingStarted()),
                    child: _AppBootstrap(locator: _locator),
                  ),
                );
              },
            );
          },
        ),
      ),
    );
  }
}

class _AppBootstrap extends StatelessWidget {
  const _AppBootstrap({required this.locator});

  final GetIt locator;

  @override
  Widget build(BuildContext context) {
    return BlocBuilder<OnboardingBloc, OnboardingState>(
      builder: (context, state) {
        return AnimatedSwitcher(
          duration: const Duration(milliseconds: 360),
          switchInCurve: Curves.easeOutCubic,
          switchOutCurve: Curves.easeInCubic,
          child: switch (state.status) {
            OnboardingFlowStatus.accepted => VpnHomePage(
              key: const ValueKey<String>('vpn_home'),
              locator: locator,
            ),
            _ => KeyedSubtree(
              key: ValueKey<String>('onboarding_${state.status.name}'),
              child: state.status == OnboardingFlowStatus.loading
                  ? const _AppLoadingView()
                  : const OnboardingPage(),
            ),
          },
        );
      },
    );
  }
}

class _AppLoadingView extends StatelessWidget {
  const _AppLoadingView();

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Scaffold(
      body: DecoratedBox(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [
              colorScheme.surface,
              colorScheme.surfaceContainerLow,
              colorScheme.surface,
            ],
          ),
        ),
        child: const Center(child: CircularProgressIndicator()),
      ),
    );
  }
}

void installGlobalErrorHooks() {
  FlutterError.onError = (FlutterErrorDetails details) {
    FlutterError.presentError(details);
    debugPrint('FlutterError: ${details.exceptionAsString()}');
    if (details.stack != null) {
      debugPrintStack(stackTrace: details.stack);
    }
  };

  PlatformDispatcher.instance.onError = (Object error, StackTrace stack) {
    debugPrint('PlatformDispatcher.onError: $error');
    debugPrintStack(stackTrace: stack);
    return true;
  };
}
