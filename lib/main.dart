/// Application entry: global error hooks, persisted theme mode, and [TunnelForgeApp].
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'pages/home_page.dart';
import 'profile_store.dart';
import 'theme.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();

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

  runApp(const TunnelForgeApp());
}

const String _kThemeModePrefsKey = 'app_theme_mode';

String _themeModeToStorage(ThemeMode mode) {
  switch (mode) {
    case ThemeMode.light:
      return 'light';
    case ThemeMode.dark:
      return 'dark';
    case ThemeMode.system:
      return 'system';
  }
}

ThemeMode _themeModeFromStorage(String? raw) {
  switch (raw) {
    case 'light':
      return ThemeMode.light;
    case 'dark':
      return ThemeMode.dark;
    default:
      return ThemeMode.system;
  }
}

class TunnelForgeApp extends StatefulWidget {
  const TunnelForgeApp({super.key, this.profileStore});

  final ProfileStore? profileStore;

  @override
  State<TunnelForgeApp> createState() => _TunnelForgeAppState();
}

class _TunnelForgeAppState extends State<TunnelForgeApp> {
  ThemeMode _themeMode = ThemeMode.system;

  @override
  void initState() {
    super.initState();
    _loadThemeMode();
  }

  Future<void> _loadThemeMode() async {
    final prefs = await SharedPreferences.getInstance();
    if (!mounted) return;
    setState(() {
      _themeMode = _themeModeFromStorage(prefs.getString(_kThemeModePrefsKey));
    });
  }

  Future<void> _setThemeMode(ThemeMode mode) async {
    setState(() => _themeMode = mode);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_kThemeModePrefsKey, _themeModeToStorage(mode));
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Tunnel Forge',
      theme: appTheme(Brightness.light),
      darkTheme: appTheme(Brightness.dark),
      themeMode: _themeMode,
      home: VpnHomePage(
        themeMode: _themeMode,
        onThemeModeChanged: _setThemeMode,
        profileStore: widget.profileStore,
      ),
    );
  }
}
