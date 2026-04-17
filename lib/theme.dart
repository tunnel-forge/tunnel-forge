import 'package:flutter/material.dart';

const Color _kSeedColor = Color(0xFF0F4C81);

/// Shared light/dark [ThemeData] for [MaterialApp] (Material 3, tuned palette).
ThemeData appTheme(Brightness brightness) {
  final baseText = ThemeData(
    brightness: brightness,
    useMaterial3: true,
  ).textTheme;
  final seedScheme = ColorScheme.fromSeed(
    seedColor: _kSeedColor,
    brightness: brightness,
    dynamicSchemeVariant: DynamicSchemeVariant.neutral,
  );
  final scheme = brightness == Brightness.light
      ? seedScheme.copyWith(
          primary: const Color(0xFF1D5D94),
          onPrimary: Colors.white,
          secondary: const Color(0xFF3559A8),
          onSecondary: Colors.white,
          tertiary: const Color(0xFF34D399),
          onTertiary: Colors.white,
          error: const Color(0xFFEB5757),
          onError: Colors.white,
          surface: const Color(0xFFFFFFFF),
          onSurface: const Color(0xFF0F172A),
          onSurfaceVariant: const Color(0xFF52637A),
          outline: const Color(0xFFD4DCE8),
          outlineVariant: const Color(0xFFE8EDF5),
          shadow: Colors.black.withValues(alpha: 0.16),
        )
      : seedScheme.copyWith(
          primary: const Color(0xFF7AB8FF),
          onPrimary: const Color(0xFF0D223F),
          secondary: const Color(0xFFA7D0FF),
          onSecondary: const Color(0xFF0D223F),
          tertiary: const Color(0xFF6EE7B7),
          onTertiary: const Color(0xFF052E16),
          error: const Color(0xFFFCA5A5),
          onError: const Color(0xFF3F0A0A),
          surface: const Color(0xFF111827),
          onSurface: const Color(0xFFE2E8F0),
          onSurfaceVariant: const Color(0xFFA3B5CC),
          outline: const Color(0xFF3B4D66),
          outlineVariant: const Color(0xFF2A374A),
          shadow: Colors.black.withValues(alpha: 0.34),
        );
  return ThemeData(
    colorScheme: scheme,
    useMaterial3: true,
    visualDensity: VisualDensity.standard,
    textTheme: baseText.copyWith(
      titleLarge: baseText.titleLarge?.copyWith(fontWeight: FontWeight.w700, letterSpacing: -0.2),
      titleMedium: baseText.titleMedium?.copyWith(fontWeight: FontWeight.w600, letterSpacing: -0.1),
      titleSmall: baseText.titleSmall?.copyWith(fontWeight: FontWeight.w600),
      bodyLarge: baseText.bodyLarge?.copyWith(height: 1.35),
      bodyMedium: baseText.bodyMedium?.copyWith(height: 1.4),
      bodySmall: baseText.bodySmall?.copyWith(height: 1.35),
      labelLarge: baseText.labelLarge?.copyWith(fontWeight: FontWeight.w600, letterSpacing: 0.1),
    ),
    appBarTheme: const AppBarTheme(centerTitle: false, scrolledUnderElevation: 0),
    inputDecorationTheme: InputDecorationTheme(
      filled: false,
      isDense: true,
      border: OutlineInputBorder(borderRadius: BorderRadius.circular(8)),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
      ),
    ),
    snackBarTheme: const SnackBarThemeData(behavior: SnackBarBehavior.floating),
  );
}
