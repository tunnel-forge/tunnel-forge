import 'package:flutter/material.dart';

/// Neutral structural palette plus semantic state colors.
abstract final class AppPalette {
  static const Color lightPrimary = Color(0xFF1B1F24);
  static const Color lightOnPrimary = Colors.white;
  static const Color lightPrimaryContainer = Color(0xFFE7EAEC);
  static const Color lightOnPrimaryContainer = Color(0xFF12161B);
  static const Color lightSecondary = Color(0xFF2D6A4F);
  static const Color lightOnSecondary = Colors.white;
  static const Color lightSecondaryContainer = Color(0xFFDDEEE4);
  static const Color lightOnSecondaryContainer = Color(0xFF163323);
  static const Color lightTertiary = Color(0xFF16A34A);
  static const Color lightOnTertiary = Colors.white;
  static const Color lightTertiaryContainer = Color(0xFFD9F4E6);
  static const Color lightOnTertiaryContainer = Color(0xFF0E3525);
  static const Color lightError = Color(0xFFFF6961);
  static const Color lightOnError = Colors.white;
  static const Color lightSurface = Color(0xFFF6F7F8);
  static const Color lightSurfaceDim = Color(0xFFF0F2F4);
  static const Color lightSurfaceBright = Colors.white;
  static const Color lightSurfaceContainerLowest = Colors.white;
  static const Color lightSurfaceContainerLow = Color(0xFFF8FAFB);
  static const Color lightSurfaceContainer = Color(0xFFFFFFFF);
  static const Color lightSurfaceContainerHigh = Color(0xFFF1F3F4);
  static const Color lightSurfaceContainerHighest = Color(0xFFE7EAEC);
  static const Color lightOnSurface = Color(0xFF12161B);
  static const Color lightOnSurfaceVariant = Color(0xFF5D6976);
  static const Color lightOutline = Color(0xFFC7CED6);
  static const Color lightOutlineVariant = Color(0xFFE1E6EB);
  static const Color darkPrimary = Color(0xFFECEFF2);
  static const Color darkOnPrimary = Color(0xFF12161B);
  static const Color darkPrimaryContainer = Color(0xFF222A32);
  static const Color darkOnPrimaryContainer = Color(0xFFF3F5F7);
  static const Color darkSecondary = Color(0xFFA6D8B8);
  static const Color darkOnSecondary = Color(0xFF102117);
  static const Color darkSecondaryContainer = Color(0xFF1A2B21);
  static const Color darkOnSecondaryContainer = Color(0xFFD7F0E0);
  static const Color darkTertiary = Color(0xFF6FD39A);
  static const Color darkOnTertiary = Color(0xFF0B2418);
  static const Color darkTertiaryContainer = Color(0xFF173225);
  static const Color darkOnTertiaryContainer = Color(0xFFC9F2DA);
  static const Color darkError = Color(0xFFFF6961);
  static const Color darkOnError = Color(0xFF5C120C);
  static const Color darkSurface = Color(0xFF0E1114);
  static const Color darkSurfaceDim = Color(0xFF0A0D10);
  static const Color darkSurfaceBright = Color(0xFF191E24);
  static const Color darkSurfaceContainerLowest = Color(0xFF0A0D10);
  static const Color darkSurfaceContainerLow = Color(0xFF12161B);
  static const Color darkSurfaceContainer = Color(0xFF151A1F);
  static const Color darkSurfaceContainerHigh = Color(0xFF1B2127);
  static const Color darkSurfaceContainerHighest = Color(0xFF222A32);
  static const Color darkOnSurface = Color(0xFFF3F5F7);
  static const Color darkOnSurfaceVariant = Color(0xFFA6B0BA);
  static const Color darkOutline = Color(0xFF3B4550);
  static const Color darkOutlineVariant = Color(0xFF2A323A);
}

@immutable
class AppSemanticColors extends ThemeExtension<AppSemanticColors> {
  const AppSemanticColors({
    required this.connectIdle,
    required this.onConnectIdle,
    required this.connected,
    required this.onConnected,
    required this.connecting,
    required this.onConnecting,
    required this.disconnect,
    required this.onDisconnect,
    required this.info,
    required this.onInfo,
  });

  final Color connectIdle;
  final Color onConnectIdle;
  final Color connected;
  final Color onConnected;
  final Color connecting;
  final Color onConnecting;
  final Color disconnect;
  final Color onDisconnect;
  final Color info;
  final Color onInfo;

  const AppSemanticColors.light()
    : this(
        connectIdle: AppPalette.lightPrimary,
        onConnectIdle: AppPalette.lightOnPrimary,
        connected: AppPalette.lightTertiary,
        onConnected: AppPalette.lightOnTertiary,
        connecting: const Color(0xFFB87503),
        onConnecting: Colors.white,
        disconnect: AppPalette.lightError,
        onDisconnect: AppPalette.lightOnError,
        info: AppPalette.lightSecondary,
        onInfo: AppPalette.lightOnSecondary,
      );

  const AppSemanticColors.dark()
    : this(
        connectIdle: AppPalette.darkPrimary,
        onConnectIdle: AppPalette.darkOnPrimary,
        connected: AppPalette.darkTertiary,
        onConnected: AppPalette.darkOnTertiary,
        connecting: const Color(0xFFF0BE62),
        onConnecting: const Color(0xFF1B1B1F),
        disconnect: AppPalette.darkError,
        onDisconnect: AppPalette.darkOnError,
        info: AppPalette.darkSecondary,
        onInfo: AppPalette.darkOnSecondary,
      );

  static AppSemanticColors fallback(Brightness brightness) =>
      brightness == Brightness.light
      ? const AppSemanticColors.light()
      : const AppSemanticColors.dark();

  @override
  AppSemanticColors copyWith({
    Color? connectIdle,
    Color? onConnectIdle,
    Color? connected,
    Color? onConnected,
    Color? connecting,
    Color? onConnecting,
    Color? disconnect,
    Color? onDisconnect,
    Color? info,
    Color? onInfo,
  }) {
    return AppSemanticColors(
      connectIdle: connectIdle ?? this.connectIdle,
      onConnectIdle: onConnectIdle ?? this.onConnectIdle,
      connected: connected ?? this.connected,
      onConnected: onConnected ?? this.onConnected,
      connecting: connecting ?? this.connecting,
      onConnecting: onConnecting ?? this.onConnecting,
      disconnect: disconnect ?? this.disconnect,
      onDisconnect: onDisconnect ?? this.onDisconnect,
      info: info ?? this.info,
      onInfo: onInfo ?? this.onInfo,
    );
  }

  @override
  AppSemanticColors lerp(
    covariant ThemeExtension<AppSemanticColors>? other,
    double t,
  ) {
    if (other is! AppSemanticColors) return this;
    return AppSemanticColors(
      connectIdle: Color.lerp(connectIdle, other.connectIdle, t) ?? connectIdle,
      onConnectIdle:
          Color.lerp(onConnectIdle, other.onConnectIdle, t) ?? onConnectIdle,
      connected: Color.lerp(connected, other.connected, t) ?? connected,
      onConnected: Color.lerp(onConnected, other.onConnected, t) ?? onConnected,
      connecting: Color.lerp(connecting, other.connecting, t) ?? connecting,
      onConnecting:
          Color.lerp(onConnecting, other.onConnecting, t) ?? onConnecting,
      disconnect: Color.lerp(disconnect, other.disconnect, t) ?? disconnect,
      onDisconnect:
          Color.lerp(onDisconnect, other.onDisconnect, t) ?? onDisconnect,
      info: Color.lerp(info, other.info, t) ?? info,
      onInfo: Color.lerp(onInfo, other.onInfo, t) ?? onInfo,
    );
  }
}

const Color _kSeedColor = AppPalette.lightPrimary;

/// Shared light/dark [ThemeData] for [MaterialApp] (Material 3, neutral base).
ThemeData appTheme(Brightness brightness, {String fontFamily = 'Estedad'}) {
  final baseTheme = ThemeData(
    brightness: brightness,
    fontFamily: fontFamily,
    useMaterial3: true,
  );
  final baseText = baseTheme.textTheme;
  final seedScheme = ColorScheme.fromSeed(
    seedColor: _kSeedColor,
    brightness: brightness,
    dynamicSchemeVariant: DynamicSchemeVariant.neutral,
  );
  final scheme = brightness == Brightness.light
      ? seedScheme.copyWith(
          primary: AppPalette.lightPrimary,
          onPrimary: AppPalette.lightOnPrimary,
          primaryContainer: AppPalette.lightPrimaryContainer,
          onPrimaryContainer: AppPalette.lightOnPrimaryContainer,
          secondary: AppPalette.lightSecondary,
          onSecondary: AppPalette.lightOnSecondary,
          secondaryContainer: AppPalette.lightSecondaryContainer,
          onSecondaryContainer: AppPalette.lightOnSecondaryContainer,
          tertiary: AppPalette.lightTertiary,
          onTertiary: AppPalette.lightOnTertiary,
          tertiaryContainer: AppPalette.lightTertiaryContainer,
          onTertiaryContainer: AppPalette.lightOnTertiaryContainer,
          error: AppPalette.lightError,
          onError: AppPalette.lightOnError,
          surface: AppPalette.lightSurface,
          surfaceDim: AppPalette.lightSurfaceDim,
          surfaceBright: AppPalette.lightSurfaceBright,
          surfaceContainerLowest: AppPalette.lightSurfaceContainerLowest,
          surfaceContainerLow: AppPalette.lightSurfaceContainerLow,
          surfaceContainer: AppPalette.lightSurfaceContainer,
          surfaceContainerHigh: AppPalette.lightSurfaceContainerHigh,
          surfaceContainerHighest: AppPalette.lightSurfaceContainerHighest,
          onSurface: AppPalette.lightOnSurface,
          onSurfaceVariant: AppPalette.lightOnSurfaceVariant,
          outline: AppPalette.lightOutline,
          outlineVariant: AppPalette.lightOutlineVariant,
          shadow: Colors.black.withValues(alpha: 0.16),
        )
      : seedScheme.copyWith(
          primary: AppPalette.darkPrimary,
          onPrimary: AppPalette.darkOnPrimary,
          primaryContainer: AppPalette.darkPrimaryContainer,
          onPrimaryContainer: AppPalette.darkOnPrimaryContainer,
          secondary: AppPalette.darkSecondary,
          onSecondary: AppPalette.darkOnSecondary,
          secondaryContainer: AppPalette.darkSecondaryContainer,
          onSecondaryContainer: AppPalette.darkOnSecondaryContainer,
          tertiary: AppPalette.darkTertiary,
          onTertiary: AppPalette.darkOnTertiary,
          tertiaryContainer: AppPalette.darkTertiaryContainer,
          onTertiaryContainer: AppPalette.darkOnTertiaryContainer,
          error: AppPalette.darkError,
          onError: AppPalette.darkOnError,
          surface: AppPalette.darkSurface,
          surfaceDim: AppPalette.darkSurfaceDim,
          surfaceBright: AppPalette.darkSurfaceBright,
          surfaceContainerLowest: AppPalette.darkSurfaceContainerLowest,
          surfaceContainerLow: AppPalette.darkSurfaceContainerLow,
          surfaceContainer: AppPalette.darkSurfaceContainer,
          surfaceContainerHigh: AppPalette.darkSurfaceContainerHigh,
          surfaceContainerHighest: AppPalette.darkSurfaceContainerHighest,
          onSurface: AppPalette.darkOnSurface,
          onSurfaceVariant: AppPalette.darkOnSurfaceVariant,
          outline: AppPalette.darkOutline,
          outlineVariant: AppPalette.darkOutlineVariant,
          shadow: Colors.black.withValues(alpha: 0.34),
        );
  final inputBorder = OutlineInputBorder(
    borderRadius: BorderRadius.circular(12),
    borderSide: BorderSide(color: scheme.outlineVariant),
  );
  return ThemeData(
    colorScheme: scheme,
    fontFamily: fontFamily,
    useMaterial3: true,
    visualDensity: VisualDensity.standard,
    extensions: <ThemeExtension<dynamic>>[
      brightness == Brightness.light
          ? const AppSemanticColors.light()
          : const AppSemanticColors.dark(),
    ],
    scaffoldBackgroundColor: scheme.surface,
    canvasColor: scheme.surface,
    textTheme: baseText.copyWith(
      titleLarge: baseText.titleLarge?.copyWith(
        fontWeight: FontWeight.w700,
        letterSpacing: 0,
      ),
      titleMedium: baseText.titleMedium?.copyWith(
        fontWeight: FontWeight.w600,
        letterSpacing: 0,
      ),
      titleSmall: baseText.titleSmall?.copyWith(fontWeight: FontWeight.w600),
      bodyLarge: baseText.bodyLarge?.copyWith(height: 1.35),
      bodyMedium: baseText.bodyMedium?.copyWith(height: 1.4),
      bodySmall: baseText.bodySmall?.copyWith(height: 1.35),
      labelLarge: baseText.labelLarge?.copyWith(
        fontWeight: FontWeight.w600,
        letterSpacing: 0.1,
      ),
    ),
    appBarTheme: AppBarTheme(
      centerTitle: false,
      scrolledUnderElevation: 0,
      backgroundColor: scheme.surface,
      foregroundColor: scheme.onSurface,
    ),
    bottomSheetTheme: BottomSheetThemeData(
      backgroundColor: brightness == Brightness.light
          ? AppPalette.lightSurfaceContainerLow
          : AppPalette.darkSurfaceContainer,
      surfaceTintColor: Colors.transparent,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: brightness == Brightness.light
          ? AppPalette.lightSurfaceContainer
          : AppPalette.darkSurfaceContainer,
      isDense: true,
      border: inputBorder,
      enabledBorder: inputBorder,
      focusedBorder: inputBorder.copyWith(
        borderSide: BorderSide(color: scheme.primary, width: 1.4),
      ),
      errorBorder: inputBorder.copyWith(
        borderSide: BorderSide(color: scheme.error),
      ),
      focusedErrorBorder: inputBorder.copyWith(
        borderSide: BorderSide(color: scheme.error, width: 1.4),
      ),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
      ),
    ),
    cardTheme: CardThemeData(
      color: brightness == Brightness.light
          ? AppPalette.lightSurfaceContainer
          : AppPalette.darkSurfaceContainer,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
      margin: EdgeInsets.zero,
    ),
    snackBarTheme: const SnackBarThemeData(behavior: SnackBarBehavior.floating),
  );
}
