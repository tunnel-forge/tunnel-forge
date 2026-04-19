import 'dart:async';

import 'package:flutter/foundation.dart';

import 'log_entry.dart';

/// Append-only log lines with a hard cap; notifies listeners once per microtask burst.
class LogBuffer extends ChangeNotifier {
  static const int _maxLines = 10000;
  final List<LogEntry> _entries = <LogEntry>[];
  bool _notifyScheduled = false;

  int get length => _entries.length;
  bool get isEmpty => _entries.isEmpty;
  List<LogEntry> get entries => List<LogEntry>.unmodifiable(_entries);

  void append(LogEntry entry) {
    _entries.add(entry);
    if (_entries.length > _maxLines) {
      _entries.removeRange(0, _entries.length - _maxLines);
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
    if (_entries.isEmpty) return;
    _entries.clear();
    notifyListeners();
  }

  String joinLines() => _entries.map((entry) => entry.toPlainText()).join('\n');
}
