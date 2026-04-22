import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:get_it/get_it.dart';

import '../connectivity_checker.dart';
import '../profile_store.dart';
import '../profile_transfer_bridge.dart';
import '../theme.dart';
import '../vpn_client.dart';
import '../features/app_theme/presentation/bloc/app_theme_bloc.dart';
import '../features/home/presentation/pages/home_page.dart';
import 'di/injection.dart';

class TunnelForgeApp extends StatefulWidget {
  const TunnelForgeApp({
    super.key,
    this.profileStore,
    this.connectivityChecker,
    this.vpnClient,
    this.profileTransferBridge,
  });

  final ProfileStore? profileStore;
  final ConnectivityChecker? connectivityChecker;
  final VpnClient? vpnClient;
  final ProfileTransferBridge? profileTransferBridge;

  @override
  State<TunnelForgeApp> createState() => _TunnelForgeAppState();
}

class _TunnelForgeAppState extends State<TunnelForgeApp> {
  late final GetIt _locator;

  @override
  void initState() {
    super.initState();
    _locator = createAppLocator(
      profileStore: widget.profileStore,
      connectivityChecker: widget.connectivityChecker,
      vpnClient: widget.vpnClient,
      profileTransferBridge: widget.profileTransferBridge,
    );
  }

  @override
  void dispose() {
    disposeLocator(_locator);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return BlocProvider<AppThemeBloc>(
      create: (_) => _locator<AppThemeBloc>()..add(const AppThemeStarted()),
      child: BlocBuilder<AppThemeBloc, AppThemeState>(
        builder: (context, state) {
          return MaterialApp(
            title: 'Tunnel Forge',
            theme: appTheme(Brightness.light),
            darkTheme: appTheme(Brightness.dark),
            themeMode: state.themeMode,
            home: VpnHomePage(locator: _locator),
          );
        },
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
