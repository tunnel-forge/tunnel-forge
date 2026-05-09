import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tunnel_forge/core/logging/log_entry.dart';
import 'package:tunnel_forge/features/home/data/home_repositories_impl.dart';

void main() {
  test('loads persisted log entries from SharedPreferences', () async {
    SharedPreferences.setMockInitialValues({
      LogsRepositoryImpl.prefsKeyLogsJson: jsonEncode([
        {
          'timestamp': '2026-01-02T03:04:05.000',
          'level': 'warning',
          'source': 'native',
          'tag': 'proxy_lwip',
          'message': 'persisted entry',
        },
      ]),
    });
    final prefs = await SharedPreferences.getInstance();
    final repository = LogsRepositoryImpl(prefsOverride: prefs);

    await repository.loadPersisted();

    expect(repository.entries, hasLength(1));
    final entry = repository.entries.single;
    expect(entry.level, LogLevel.warning);
    expect(entry.source, LogSource.native);
    expect(entry.tag, 'proxy_lwip');
    expect(entry.message, 'persisted entry');
  });

  test(
    'append persists log entries and clear removes persisted storage',
    () async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();
      final repository = LogsRepositoryImpl(prefsOverride: prefs);

      repository.append(
        LogEntry(
          timestamp: DateTime(2026, 1, 2, 3, 4, 5),
          level: LogLevel.error,
          source: LogSource.dart,
          tag: 'LogsRepository',
          message: 'saved entry',
        ),
      );
      await Future<void>.delayed(Duration.zero);

      final raw = prefs.getString(LogsRepositoryImpl.prefsKeyLogsJson);
      expect(raw, isNotNull);
      final decoded = jsonDecode(raw!) as List<dynamic>;
      expect(decoded.single, containsPair('level', 'error'));
      expect(decoded.single, containsPair('source', 'dart'));
      expect(decoded.single, containsPair('message', 'saved entry'));

      repository.clear();
      await Future<void>.delayed(Duration.zero);

      expect(prefs.getString(LogsRepositoryImpl.prefsKeyLogsJson), isNull);
    },
  );
}
