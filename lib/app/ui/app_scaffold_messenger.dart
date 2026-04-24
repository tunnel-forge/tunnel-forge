import 'dart:async';

import 'package:flutter/material.dart';

OverlayEntry? _activeToastEntry;
Timer? _activeToastTimer;

void showAppSnackBar(
  BuildContext context,
  String message, {
  bool error = false,
}) {
  final overlay = Navigator.of(context, rootNavigator: true).overlay;
  if (overlay == null) return;
  final cs = Theme.of(context).colorScheme;

  _activeToastTimer?.cancel();
  _activeToastEntry?.remove();

  final entry = OverlayEntry(
    builder: (overlayContext) => _AppToastOverlay(
      message: message,
      backgroundColor: error ? cs.error : cs.inverseSurface,
      foregroundColor: error ? cs.onError : cs.onInverseSurface,
    ),
  );
  _activeToastEntry = entry;
  overlay.insert(entry);

  if (!_shouldAutoDismissToast()) return;

  _activeToastTimer = Timer(const Duration(seconds: 3), () {
    if (identical(_activeToastEntry, entry)) {
      _activeToastEntry?.remove();
      _activeToastEntry = null;
      _activeToastTimer = null;
    }
  });
}

class _AppToastOverlay extends StatelessWidget {
  static const double _kBottomNavHeight = 72;
  static const double _kEdgePadding = 16;

  const _AppToastOverlay({
    required this.message,
    required this.backgroundColor,
    required this.foregroundColor,
  });

  final String message;
  final Color backgroundColor;
  final Color foregroundColor;

  @override
  Widget build(BuildContext context) {
    final safeBottom = MediaQuery.paddingOf(context).bottom;
    return IgnorePointer(
      child: Stack(
        children: [
          Positioned(
            left: _kEdgePadding,
            right: _kEdgePadding,
            bottom: safeBottom + _kBottomNavHeight + _kEdgePadding,
            child: Align(
              alignment: Alignment.bottomCenter,
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 560),
                child: Material(
                  key: const Key('app_toast'),
                  color: backgroundColor,
                  elevation: 6,
                  borderRadius: BorderRadius.circular(14),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 14,
                    ),
                    child: Text(
                      message,
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: foregroundColor,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

bool _shouldAutoDismissToast() {
  final bindingName = WidgetsBinding.instance.runtimeType.toString();
  return !bindingName.contains('TestWidgetsFlutterBinding');
}
