import 'dart:async';

import 'package:flutter/foundation.dart';

/// Append-only log lines with a hard cap; notifies listeners once per microtask burst.
class LogBuffer extends ChangeNotifier {
  static const int _maxLines = 10000;
  final List<String> _lines = <String>[];
  bool _notifyScheduled = false;

  int get length => _lines.length;
  bool get isEmpty => _lines.isEmpty;
  List<String> get lines => List<String>.unmodifiable(_lines);

  void append(String line) {
    _lines.add(line);
    if (_lines.length > _maxLines) {
      _lines.removeRange(0, _lines.length - _maxLines);
    }
    _scheduleNotify();
  }

  void _scheduleNotify() {
    if (_notifyScheduled) return;
    _notifyScheduled = true;
    scheduleMicrotask(() {
      _notifyScheduled = false;
      if (hasListeners) notifyListeners();
    });
  }

  void clear() {
    if (_lines.isEmpty) return;
    _lines.clear();
    notifyListeners();
  }

  String joinLines() => _lines.join('\n');
}
